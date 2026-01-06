import os
import pandas as pd
import kagglehub
import pickle
import numpy as np
import random
from config import *
from sklearn.model_selection import train_test_split


class Dataset_Logic:
    dataset_url = "jacobvs/mcgill-billboard"
    dataset_path = None

    def __init__(self):
        print("Downloading dataset...")
        self.dataset_path = kagglehub.dataset_download("jacobvs/mcgill-billboard")
        print(f"Dataset downloaded to: {self.dataset_path}")
        self.download_and_process()

    def download_and_process(self):
        anno_folder = os.path.join(self.dataset_path, "annotations", "annotations")
        metadata_folder = os.path.join(self.dataset_path, "metadata", "metadata")

        chord_dict = {}
        processed_count = 0

        if not os.path.exists(anno_folder):
            print("Error: Annotation folder not found.")
            return

        for anno_id in os.listdir(anno_folder):
            lab_path = os.path.join(anno_folder, anno_id, "majmin.lab")
            chroma_path = os.path.join(metadata_folder, anno_id, "bothchroma.csv")

            if not os.path.exists(lab_path) or not os.path.exists(chroma_path):
                continue

            try:
                chroma_df = pd.read_csv(chroma_path, header=None)
                timestamps = chroma_df.iloc[:, 1].values
                features = chroma_df.iloc[:, 2:].values

                with open(lab_path, "r") as f:
                    lines = f.readlines()

                for line in lines:
                    parts = line.strip().split("\t")
                    if len(parts) < 3:
                        continue

                    start_t, end_t, chord_label = (
                        float(parts[0]),
                        float(parts[1]),
                        parts[2],
                    )

                    idx_start = np.searchsorted(timestamps, start_t)
                    idx_end = np.searchsorted(timestamps, end_t)

                    chord_chunk = features[idx_start:idx_end]

                    if chord_chunk.shape[0] > 0:
                        if chord_label not in chord_dict:
                            chord_dict[chord_label] = []
                        chord_dict[chord_label].append(chord_chunk)

                processed_count += 1
                if processed_count % 100 == 0:
                    print(f"Processed {processed_count} songs...")

            except Exception as e:
                print(f"Skipping {anno_id} due to error: {e}")
                continue

        print(f"Finished. Total unique chord labels found: {len(chord_dict)}")
        with open(CHORD_DICT_PATH, "wb") as f:
            pickle.dump(chord_dict, f)
        print("Saved to chord_dict.pkl")

    def load_and_clean_data(self, pickle_path):
        print("Loading dictionary...")
        if not os.path.exists(pickle_path):
            raise FileNotFoundError(
                f"Could not find {pickle_path}. Run process_data.py first."
            )

        with open(pickle_path, "rb") as f:
            raw_dict = pickle.load(f)

        # Correct Enharmonic Mapping
        flat_sharp_map = {
            "Bb:maj": "A#:maj",
            "Eb:maj": "D#:maj",
            "Ab:min": "G#:min",
            "Db:maj": "C#:maj",
            "Ab:maj": "G#:maj",
            "Bb:min": "A#:min",
            "Gb:maj": "F#:maj",
            "Eb:min": "D#:min",
            "Cb:maj": "B:maj",
            "Db:min": "C#:min",
            "Gb:min": "F#:min",
            "Cb:min": "B:min",
            "Fb:maj": "E:maj",
        }

        cleaned_dict = {}
        print("Cleaning labels...")
        for label, matrices in raw_dict.items():
            if label in ["N", "X"]:
                continue
            target_label = flat_sharp_map.get(label, label)
            if target_label not in cleaned_dict:
                cleaned_dict[target_label] = []
            cleaned_dict[target_label].extend(matrices)

        # Filter very rare chords
        final_dict = {}
        threshold = 500
        for label, data in cleaned_dict.items():
            if len(data) >= threshold:
                final_dict[label] = data

        print(f"Kept {len(final_dict)} chords with > {threshold} samples.")
        mx = -1
        for chord in final_dict:
            for chroma in final_dict[chord]:
                max_chroma = np.max(chroma)
                mx = max(mx, max_chroma)

        for chord in final_dict:
            for i, chroma in enumerate(final_dict[chord]):
                final_dict[chord][i] = chroma / mx

        return final_dict

    def add_noise(self, matrix, noise_factor=0.005):
        """Injects random noise to prevent overfitting."""
        noise = np.random.normal(0, noise_factor, matrix.shape)
        return matrix + noise

    def prepare_datasets(self, chord_data):
        unique_labels = sorted(list(chord_data.keys()))
        label_map = {label: i for i, label in enumerate(unique_labels)}

        # Undersample to balance classes
        min_samples = min([len(v) for v in chord_data.values()])
        print(f"Balancing: Limiting to {min_samples} samples per class.")

        X = []
        y = []

        for label, matrices in chord_data.items():
            label_id = label_map[label]
            sampled_matrices = random.sample(matrices, min_samples)

            for matrix in sampled_matrices:
                # Shape Check
                if matrix.shape[1] > CHROMA_BINS:
                    matrix = matrix[:, :CHROMA_BINS]  # Trim extra columns
                if matrix.shape[1] != CHROMA_BINS:
                    continue

                # Padding / Cropping
                time_steps = matrix.shape[0]
                if time_steps < FIXED_FRAMES:
                    pad_amt = FIXED_FRAMES - time_steps
                    padding = np.zeros((pad_amt, CHROMA_BINS))
                    matrix = np.vstack((matrix, padding))
                elif time_steps > FIXED_FRAMES:
                    start = (time_steps - FIXED_FRAMES) // 2
                    matrix = matrix[start : start + FIXED_FRAMES, :]

                # --- DATA AUGMENTATION ---
                # Add the original
                X.append(matrix)
                y.append(label_id)

                # Add a noisy version (Doubles dataset size, helps generalization)
                X.append(self.add_noise(matrix))
                y.append(label_id)

        X = np.array(X, dtype="float32")
        X = X[..., np.newaxis]
        y = np.array(y, dtype="int32")

        with open(LABEL_MAP_PATH, "wb") as f:
            pickle.dump(label_map, f)
        
        
        return X, y, label_map

    def prepare_dataset_for_training(self):
        if not os.path.exists(CHORD_DICT_PATH):
            print("Please run process_data.py first!")
            exit()

        data = self.load_and_clean_data(CHORD_DICT_PATH)
        X, y, label_map = self.prepare_datasets(data)
        print("label map:", label_map)

        print(f"Training on {len(X)} samples with {len(label_map)} classes.")

        X_train, X_test, y_train, y_test = train_test_split(
            X, y, test_size=0.15, random_state=42, stratify=y
        )

        return X_train, X_test, y_train, y_test, label_map

    def retrieve_label_map(self):
        if not os.path.exists(LABEL_MAP_PATH):
            raise FileNotFoundError(
                f"Could not find {LABEL_MAP_PATH}. Run process_data.py first."
            )

        with open(LABEL_MAP_PATH, "rb") as f:
            label_map = pickle.load(f)

        return label_map
    
if __name__ == "__main__":
    dataset_logic = Dataset_Logic()
    X_train, X_test, y_train, y_test, label_map = (
        dataset_logic.prepare_dataset_for_training()
    )
