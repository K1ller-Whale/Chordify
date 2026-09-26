"""ChordNet: frame-level acoustic model (plan 03 §2).

Input  (B, T, F): normalised bothchroma + energy (F = 25) or log-CQT (F = 288).
Output dict of per-frame logits:
    chord (V) · root (13) · bass (13) · tones (12, sigmoid) · key_tonic (13) · key_mode (2) · boundary (1)
"""
from __future__ import annotations

import math
from dataclasses import asdict, dataclass

import torch
import torch.nn.functional as F
from torch import nn

HEADS = {"root": 13, "bass": 13, "tones": 12, "key_tonic": 13, "key_mode": 2, "boundary": 1}


@dataclass
class ChordNetConfig:
    input: str = "bothchroma"  # "bothchroma" (24 chroma + energy) | "log_cqt"
    n_features: int = 25
    n_classes: int = 25
    d_model: int = 192
    encoder: str = "conformer"  # "conformer" | "bigru"
    n_layers: int = 4
    n_heads: int = 4
    ff_mult: int = 4
    conv_kernel: int = 15
    dropout: float = 0.1

    def to_dict(self) -> dict:
        return asdict(self)

    @classmethod
    def small(cls, **kw) -> "ChordNetConfig":
        return cls(**{"d_model": 256, "encoder": "bigru", "n_layers": 2, **kw})

    @classmethod
    def base(cls, **kw) -> "ChordNetConfig":
        return cls(**kw)


class ChromaFrontEnd(nn.Module):
    """Treats bass and treble chroma as two channels over 12 pitch classes, with circular
    padding on the pitch axis (C is next to B), then projects to d_model."""

    def __init__(self, d_model: int, dropout: float):
        super().__init__()
        self.conv1 = nn.Conv2d(2, 32, kernel_size=3, padding=(1, 0))
        self.bn1 = nn.BatchNorm2d(32)
        self.conv2 = nn.Conv2d(32, 64, kernel_size=3, padding=(1, 0))
        self.bn2 = nn.BatchNorm2d(64)
        self.proj = nn.Linear(64 * 12 + 1, d_model)
        self.dropout = nn.Dropout(dropout)

    @staticmethod
    def _circular(x: torch.Tensor) -> torch.Tensor:
        return torch.cat([x[..., -1:], x, x[..., :1]], dim=-1)  # pad pitch by 1 on each side

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        b, t, _ = x.shape
        chroma = x[..., :24].reshape(b, t, 2, 12).permute(0, 2, 1, 3)  # (B, 2, T, 12)
        h = F.gelu(self.bn1(self.conv1(self._circular(chroma))))
        h = F.gelu(self.bn2(self.conv2(self._circular(h))))
        h = h.permute(0, 2, 1, 3).reshape(b, t, 64 * 12)
        return self.dropout(self.proj(torch.cat([h, x[..., 24:25]], dim=-1)))


class CqtFrontEnd(nn.Module):
    """Log-CQT (3 bins/semitone) -> conv + frequency pooling -> d_model."""

    def __init__(self, n_bins: int, d_model: int, dropout: float):
        super().__init__()
        self.convs = nn.Sequential(
            nn.Conv2d(1, 32, 3, padding=1), nn.BatchNorm2d(32), nn.GELU(), nn.MaxPool2d((1, 3)),
            nn.Conv2d(32, 64, 3, padding=1), nn.BatchNorm2d(64), nn.GELU(), nn.MaxPool2d((1, 2)),
            nn.Conv2d(64, 64, 3, padding=1), nn.BatchNorm2d(64), nn.GELU(), nn.MaxPool2d((1, 2)),
        )
        self.proj = nn.Linear(64 * (n_bins // 12), d_model)
        self.dropout = nn.Dropout(dropout)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        b, t, _ = x.shape
        h = self.convs(x.unsqueeze(1))  # (B, 64, T, n_bins/12)
        return self.dropout(self.proj(h.permute(0, 2, 1, 3).reshape(b, t, -1)))


class FeedForward(nn.Module):
    def __init__(self, d: int, mult: int, dropout: float):
        super().__init__()
        self.net = nn.Sequential(nn.LayerNorm(d), nn.Linear(d, d * mult), nn.SiLU(), nn.Dropout(dropout),
                                 nn.Linear(d * mult, d), nn.Dropout(dropout))

    def forward(self, x):
        return self.net(x)


class ConvModule(nn.Module):
    def __init__(self, d: int, kernel: int, dropout: float):
        super().__init__()
        self.norm = nn.LayerNorm(d)
        self.pointwise_in = nn.Conv1d(d, 2 * d, 1)
        self.depthwise = nn.Conv1d(d, d, kernel, padding=kernel // 2, groups=d)
        self.bn = nn.BatchNorm1d(d)
        self.pointwise_out = nn.Conv1d(d, d, 1)
        self.dropout = nn.Dropout(dropout)

    def forward(self, x):
        h = self.norm(x).transpose(1, 2)
        h = F.glu(self.pointwise_in(h), dim=1)
        h = F.silu(self.bn(self.depthwise(h)))
        return self.dropout(self.pointwise_out(h).transpose(1, 2))


class SelfAttention(nn.Module):
    """Multi-head self-attention whose reshapes never bake in the sequence length, so the
    ONNX export works for any number of frames (nn.MultiheadAttention's trace does not)."""

    def __init__(self, d: int, heads: int, dropout: float):
        super().__init__()
        self.heads, self.head_dim, self.dropout = heads, d // heads, dropout
        self.qkv = nn.Linear(d, 3 * d)
        self.out = nn.Linear(d, d)

    def forward(self, x: torch.Tensor, key_padding_mask: torch.Tensor | None = None) -> torch.Tensor:
        b = x.shape[0]
        qkv = self.qkv(x).reshape(b, -1, 3, self.heads, self.head_dim).permute(2, 0, 3, 1, 4)
        mask = None if key_padding_mask is None else ~key_padding_mask[:, None, None, :]
        h = F.scaled_dot_product_attention(qkv[0], qkv[1], qkv[2], attn_mask=mask,
                                           dropout_p=self.dropout if self.training else 0.0)
        return self.out(h.transpose(1, 2).reshape(b, -1, self.heads * self.head_dim))


class ConformerBlock(nn.Module):
    def __init__(self, d: int, heads: int, ff_mult: int, kernel: int, dropout: float):
        super().__init__()
        self.ff1 = FeedForward(d, ff_mult, dropout)
        self.attn_norm = nn.LayerNorm(d)
        self.attn = SelfAttention(d, heads, dropout)
        self.attn_dropout = nn.Dropout(dropout)
        self.conv = ConvModule(d, kernel, dropout)
        self.ff2 = FeedForward(d, ff_mult, dropout)
        self.out_norm = nn.LayerNorm(d)

    def forward(self, x, key_padding_mask=None):
        x = x + 0.5 * self.ff1(x)
        x = x + self.attn_dropout(self.attn(self.attn_norm(x), key_padding_mask))
        x = x + self.conv(x)
        x = x + 0.5 * self.ff2(x)
        return self.out_norm(x)


def sinusoidal_positions(t: int, d: int, device) -> torch.Tensor:
    position = torch.arange(t, device=device, dtype=torch.float32).unsqueeze(1)
    div = torch.exp(torch.arange(0, d, 2, device=device, dtype=torch.float32) * (-math.log(10000.0) / d))
    pe = torch.zeros(t, d, device=device)
    pe[:, 0::2] = torch.sin(position * div)
    pe[:, 1::2] = torch.cos(position * div)
    return pe


class ChordNet(nn.Module):
    def __init__(self, config: ChordNetConfig):
        super().__init__()
        self.config = config
        d = config.d_model
        if config.input == "bothchroma":
            self.front = ChromaFrontEnd(d, config.dropout)
        else:
            self.front = CqtFrontEnd(config.n_features, d, config.dropout)
        if config.encoder == "conformer":
            self.blocks = nn.ModuleList(ConformerBlock(d, config.n_heads, config.ff_mult, config.conv_kernel,
                                                       config.dropout) for _ in range(config.n_layers))
            self.gru = None
        else:
            self.blocks = nn.ModuleList()
            self.gru = nn.GRU(d, d // 2, num_layers=config.n_layers, batch_first=True, bidirectional=True,
                              dropout=config.dropout if config.n_layers > 1 else 0.0)
        self.heads = nn.ModuleDict({"chord": nn.Linear(d, config.n_classes),
                                    **{name: nn.Linear(d, size) for name, size in HEADS.items()}})

    def forward(self, x: torch.Tensor, key_padding_mask: torch.Tensor | None = None) -> dict[str, torch.Tensor]:
        h = self.front(x)
        if self.gru is not None:
            h, _ = self.gru(h)
        else:
            h = h + sinusoidal_positions(h.shape[1], h.shape[2], h.device)
            for block in self.blocks:
                h = block(h, key_padding_mask)
        out = {name: head(h) for name, head in self.heads.items()}
        out["boundary"] = out["boundary"].squeeze(-1)
        return out


def count_parameters(model: nn.Module) -> int:
    return sum(p.numel() for p in model.parameters() if p.requires_grad)
