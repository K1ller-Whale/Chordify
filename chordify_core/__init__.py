"""Shared chord-recognition library used by training (chordify_ai) and serving (chordify_backend).

Feature extraction, chord vocabularies, decoding and music theory live here exactly
once, so the model sees the same signal in training and in production.
"""

__version__ = "0.2.0"
