import tensorflow as tf
from sklearn.model_selection import train_test_split
from tensorflow.keras import layers, models, regularizers, callbacks  # type: ignore
from config import *
import numpy as np


class CRNN_Model:
    def build_robust_crnn(self, input_shape, num_classes):
        self.model = models.Sequential()

        # CNN Block 1
        self.model.add(
            layers.Conv2D(
                32,
                (3, 3),
                padding="same",
                input_shape=input_shape,
                kernel_regularizer=regularizers.l2(0.001),
            )
        )
        self.model.add(layers.BatchNormalization())
        self.model.add(layers.Activation("relu"))
        self.model.add(layers.MaxPooling2D(pool_size=(2, 1)))
        self.model.add(layers.Dropout(0.2))

        # CNN Block 2
        self.model.add(
            layers.Conv2D(
                64, (3, 3), padding="same", kernel_regularizer=regularizers.l2(0.001)
            )
        )
        self.model.add(layers.BatchNormalization())
        self.model.add(layers.Activation("relu"))
        self.model.add(layers.MaxPooling2D(pool_size=(2, 2)))
        self.model.add(layers.Dropout(0.3))

        # CNN Block 3
        self.model.add(
            layers.Conv2D(
                128, (3, 3), padding="same", kernel_regularizer=regularizers.l2(0.001)
            )
        )
        self.model.add(layers.BatchNormalization())
        self.model.add(layers.Activation("relu"))
        self.model.add(layers.MaxPooling2D(pool_size=(2, 2)))
        self.model.add(layers.Dropout(0.3))

        # Reshape for RNN
        # Calculates (Time, Features)
        new_shape = (-1, self.model.output_shape[2] * self.model.output_shape[3])
        self.model.add(layers.Reshape(new_shape))

        # RNN Block
        self.model.add(layers.Bidirectional(layers.LSTM(128, return_sequences=False)))
        self.model.add(layers.Dropout(0.4))

        # Dense Block
        self.model.add(layers.Dense(256, kernel_regularizer=regularizers.l2(0.001)))
        self.model.add(layers.BatchNormalization())
        self.model.add(layers.Activation("relu"))
        self.model.add(layers.Dropout(0.5))

        self.model.add(layers.Dense(num_classes, activation="softmax"))

        optimizer = tf.keras.optimizers.Adam(learning_rate=LEARNING_RATE)

        self.model.compile(
            optimizer=optimizer,
            loss="sparse_categorical_crossentropy",
            metrics=["accuracy"],
        )

        self.callbacks_list = [
            # Stop if val_loss doesn't improve for 8 epochs
            callbacks.EarlyStopping(
                monitor="val_loss", patience=8, restore_best_weights=True, verbose=1
            ),
            # Reduce LR if stuck for 3 epochs (helps converge when loss is bouncing)
            callbacks.ReduceLROnPlateau(
                monitor="val_loss", factor=0.2, patience=3, min_lr=1e-6, verbose=1
            ),
        ]

        self.model.summary()

    def train(self, X_train, y_train, X_val, y_val):
        history = self.model.fit(
            X_train,
            y_train,
            validation_data=(X_val, y_val),
            epochs=EPOCHS,
            batch_size=BATCH_SIZE,
            callbacks=self.callbacks_list,
        )
        return history

    def save(self, file_path=MODEL_SAVE_FILE):
        models.save_model(self.model, file_path)
        print(f"Model saved to {file_path}")

    def load(self, file_path=MODEL_SAVE_FILE):
        self.model = models.load_model(file_path)
        print(f"Model loaded from {file_path}")

    def evaluate_single_record(self, chroma):
        new_chorma = chroma[..., np.newaxis]
        new_chorma = np.expand_dims(new_chorma, 0)
        prediction = self.model.predict(new_chorma)

        predicted_idx = np.argmax(prediction)
        confidence = float(np.max(prediction))

        return predicted_idx, confidence
