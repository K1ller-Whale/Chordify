import os

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

MODELS_DIR = "chordify_ai/assets/models/"
LABELS_DIR = "chordify_ai/assets/"

MODEL_PATH = os.path.join(MODELS_DIR, "chord_crnn_model_v1.keras")
LABEL_MAP_PATH = os.path.join(LABELS_DIR, "label_map.pkl")

SAMPLE_RATE = 22050
HOP_LENGTH = 512
