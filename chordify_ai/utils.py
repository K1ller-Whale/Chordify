import librosa
import vamp
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
from sklearn.metrics import confusion_matrix, classification_report
import os
from config import *


class Utils:
    def extract_mcgill_style_features(audio_path, target_frames=100):
        data, rate = librosa.load(audio_path, sr=44100, mono=True)

        chroma_res = vamp.collect(
            data, rate, "nnls-chroma:nnls-chroma", output="chroma"
        )
        bass_res = vamp.collect(
            data, rate, "nnls-chroma:nnls-chroma", output="basschroma"
        )

        def get_data_from_res(res, name):
            """Helper to safely extract the numpy array from vamp results"""
            if "matrix" in res:
                return res["matrix"][1]

            if "vector" in res:
                return res["vector"][2]

            if "list" in res:
                return np.array([x["values"] for x in res["list"]])

            raise KeyError(
                f"Could not find data key in {name} output. "
                f"Available keys are: {list(res.keys())}"
            )

        chroma_vals = get_data_from_res(chroma_res, "Chroma")
        bass_vals = get_data_from_res(bass_res, "Bass")

        min_len = min(len(chroma_vals), len(bass_vals))
        chroma_vals = chroma_vals[:min_len]
        bass_vals = bass_vals[:min_len]

        both_chroma = np.hstack((bass_vals, chroma_vals))

        if np.max(both_chroma) > 0:
            both_chroma = both_chroma / np.max(both_chroma)

        if both_chroma.shape[0] < target_frames:
            padding = np.zeros((target_frames - both_chroma.shape[0], 24))
            both_chroma = np.vstack((both_chroma, padding))
        else:
            start = (both_chroma.shape[0] - target_frames) // 2
            both_chroma = both_chroma[start : start + target_frames]

        return both_chroma

    def visualize_chroma(chroma, sr=22050, hop_length=512, title="Chromagram"):
        plt.figure(figsize=(14, 6))

        librosa.display.specshow(
            chroma,
            y_axis="chroma",
            x_axis="time",
            hop_length=hop_length,
            sr=sr,
            cmap="coolwarm",
        )

        plt.colorbar(label="Chroma Magnitude")
        plt.title(title)
        plt.xlabel("Time (seconds)")
        plt.ylabel("Pitch Class")
        plt.tight_layout()
        plt.show()

    def save_training_plot(history, filename="training_results.png"):
        acc = history.history["accuracy"]
        val_acc = history.history["val_accuracy"]
        loss = history.history["loss"]
        val_loss = history.history["val_loss"]
        epochs_range = range(len(acc))

        plt.figure(figsize=(12, 6))

        # Plot Accuracy
        plt.subplot(1, 2, 1)
        plt.plot(epochs_range, acc, label="Training Accuracy")
        plt.plot(epochs_range, val_acc, label="Validation Accuracy")
        plt.legend(loc="lower right")
        plt.title("Training and Validation Accuracy")
        plt.grid(True)

        # Plot Loss
        plt.subplot(1, 2, 2)
        plt.plot(epochs_range, loss, label="Training Loss")
        plt.plot(epochs_range, val_loss, label="Validation Loss")
        plt.legend(loc="upper right")
        plt.title("Training and Validation Loss")
        plt.grid(True)

        plt.savefig(os.path.join(PLOTTING_PATH, filename))
        print(f"\nGraph saved to: {os.path.abspath(filename)}")
        plt.close()

    def plot_chord_confusion_matrix(model, x_test, y_test, class_names):
        """
        model: Your trained CNN+LSTM model
        x_test: Test features (Shape: Samples, Frames, 24, 1)
        y_test: True labels (Integers)
        class_names: List of chord names (e.g., ['C', 'G', 'Am', ...])
        """

        print("Generating predictions...")
        y_pred_probs = model.predict(x_test)

        y_pred = np.argmax(y_pred_probs, axis=1)

        if len(y_test.shape) > 1:
            y_true = np.argmax(y_test, axis=1)
        else:
            y_true = y_test
        cm = confusion_matrix(y_true, y_pred)

        cm_normalized = cm.astype("float") / cm.sum(axis=1)[:, np.newaxis]

        plt.figure(figsize=(12, 10))
        sns.heatmap(
            cm_normalized,
            annot=True,
            fmt=".2f",
            cmap="Blues",
            xticklabels=class_names,
            yticklabels=class_names,
        )

        plt.title("Normalized Confusion Matrix: Chord Recognition")
        plt.ylabel("True Chord")
        plt.xlabel("Predicted Chord")
        plt.show()
        plt.savefig(os.path.join(PLOTTING_PATH, "confusion_matrix.png"))

        print("\nClassification Report:\n")
        print(classification_report(y_true, y_pred, target_names=class_names))
