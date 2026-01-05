import os

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

ASSETS_DIR = os.path.join(BASE_DIR, "assets")
MODELS_DIR = os.path.join(ASSETS_DIR, "models")
LABELS_DIR = os.path.join(ASSETS_DIR, "label_map")

MODEL_PATH = os.path.join(MODELS_DIR, "chord_crnn_augmented.keras")
LABEL_MAP_PATH = os.path.join(LABELS_DIR, "label_map_augmented.pkl")

SAMPLE_RATE = 22050
FIXED_FRAMES = 100   # ~4.6 seconds context
CHROMA_BINS = 24     # Double chroma (12x2)
HOP_LENGTH = 512