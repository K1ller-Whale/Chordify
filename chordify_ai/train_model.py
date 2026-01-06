from crnn_model import CRNN_Model
from config import *
from dataset_logic import Dataset_Logic
from utils import Utils

if __name__ == "__main__":
    crnn_model = CRNN_Model()
    dataset_logic = Dataset_Logic()
    X_train, X_test, y_train, y_test, label_map = (
        dataset_logic.prepare_dataset_for_training()
    )
    crnn_model.build_robust_crnn(
        input_shape=(FIXED_FRAMES, CHROMA_BINS, 1), num_classes=len(label_map)
    )

    print("Starting training...")
    history = crnn_model.train(
        X_train,
        y_train,
        X_val=X_test,
        y_val=y_test,
    )

    Utils.plot_chord_confusion_matrix(
        crnn_model.model, X_test, y_test, list(label_map.keys())
    )

    crnn_model.save()

    Utils.save_training_plot(history)

    print("\n--- Final Evaluation ---")
    loss, acc = crnn_model.model.evaluate(X_test, y_test)
    print(f"Test Loss: {loss:.4f}")
    print(f"Test Accuracy: {acc * 100:.2f}%")
