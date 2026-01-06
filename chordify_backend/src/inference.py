# This file contains the model loader. It loads the model into the memory only once using the Singleton pattern

import pickle
import numpy as np
import tensorflow as tf
from config import *


class ChordRecognizer:
    def __init__(self):
        self.model = None
        self.idx_to_label = {}
        self.is_loaded = False

    def load_resources(self):
        print("Loading AI Model...")
        try:
            self.model = tf.keras.models.load_model(MODEL_PATH, compile=False)

            with open(LABEL_MAP_PATH, "rb") as f:
                label_map = pickle.load(f)
                self.idx_to_label = {v: k for k, v in label_map.items()}

            self.is_loaded = True
            print("AI Resources loaded successfully.")
        except Exception as e:
            print(f"CRITICAL ERROR loading resources: {e}")
            self.is_loaded = False

    def predict(self, input_tensor):

        if not self.is_loaded:
            raise RuntimeError("Model not loaded")
        
        new_chorma = input_tensor[..., np.newaxis]
        new_chorma = np.expand_dims(new_chorma, 0)
        prediction = self.model.predict(new_chorma, verbose=0)

        predicted_idx = np.argmax(prediction)
        confidence = float(np.max(prediction))
        label = self.idx_to_label.get(predicted_idx, "Unknown")

        return label, confidence


recognizer = ChordRecognizer()
