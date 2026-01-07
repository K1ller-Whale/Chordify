import { useState, useRef } from "react";
import "./App.css";

function App() {
  const [isRecording, setIsRecording] = useState(false);
  const [audioBlob, setAudioBlob] = useState(null);
  const [uploadedFile, setUploadedFile] = useState(null);
  const [prediction, setPrediction] = useState(null);
  const [chromaImage, setChromaImage] = useState(null);
  const [startTime, setStartTime] = useState("");
  const [endTime, setEndTime] = useState("");
  const [slices, setSlices] = useState([]);
  const [sliceResults, setSliceResults] = useState([]);
  const [audioSrc, setAudioSrc] = useState(null);
  const mediaRecorderRef = useRef(null);
  const audioChunksRef = useRef([]);
  const startTimeRef = useRef(null);

  const startRecording = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      mediaRecorderRef.current = new MediaRecorder(stream);
      audioChunksRef.current = [];
      startTimeRef.current = Date.now();
      // Clear any existing slices and their results when starting a new recording
      setSlices([]);
      setSliceResults([]);

      mediaRecorderRef.current.ondataavailable = (event) => {
        audioChunksRef.current.push(event.data);
      };
      audioBlob;
      mediaRecorderRef.current.onstop = () => {
        const blob = new Blob(audioChunksRef.current, { type: "audio/webm" });
        setAudioBlob(blob);
        console.log("finished recording");
        const duration = (Date.now() - startTimeRef.current) / 1000;
        if (duration < 4.5) {
          sendAudio(blob);
        } else {
          sendChroma(blob);
          setAudioSrc(URL.createObjectURL(blob));
        }
      };

      mediaRecorderRef.current.start();
      setIsRecording(true);
    } catch (error) {
      console.error("Error accessing microphone:", error);
    }
  };

  const stopRecording = () => {
    if (mediaRecorderRef.current && isRecording) {
      mediaRecorderRef.current.stop();
      setIsRecording(false);
      mediaRecorderRef.current.stream
        .getTracks()
        .forEach((track) => track.stop());
    }
  };

  const sendChroma = async (blob) => {
    const formData = new FormData();
    formData.append("file", blob, "recording.webm");

    try {
      const response = await fetch(
        "http://localhost:8000/extract_full_chroma",
        {
          method: "POST",
          body: formData,
        }
      );
      if (response.ok) {
        const imageBlob = await response.blob();
        const imageUrl = URL.createObjectURL(imageBlob);
        setChromaImage(imageUrl);
        setPrediction(null); // Clear prediction if showing chroma
      } else {
        console.error("Failed to analyze chroma");
      }
    } catch (error) {
      console.error("Error analyzing chroma:", error);
    }
  };

  const sendAudio = async (blob) => {
    const formData = new FormData();
    formData.append("file", blob, "recording.webm");

    try {
      const response = await fetch(
        "http://localhost:8000/predict?include_chroma=true",
        {
          method: "POST",
          body: formData,
        }
      );
      if (response.ok) {
        const result = await response.json();
        setPrediction(result);
        setChromaImage(null); // Clear chroma if showing prediction
        console.log("Chord prediction:", result);
      } else {
        console.error("Failed to send audio");
      }
    } catch (error) {
      console.error("Error sending audio:", error);
    }
  };

  const handleFileUpload = async (event) => {
    const file = event.target.files[0];
    if (file) {
      setUploadedFile(file);
      const audio = new Audio();
      audio.src = URL.createObjectURL(file);
      audio.onloadedmetadata = async () => {
        const duration = audio.duration;
        const formData = new FormData();
        formData.append("file", file);

        try {
          if (duration < 4.5) {
            const response = await fetch(
              "http://localhost:8000/predict?include_chroma=true",
              {
                method: "POST",
                body: formData,
              }
            );
            if (response.ok) {
              const result = await response.json();
              setPrediction(result);
              setChromaImage(null);
              console.log("Chord prediction:", result);
            } else {
              console.error("Failed to upload file");
            }
          } else {
            const response = await fetch(
              "http://localhost:8000/extract_full_chroma",
              {
                method: "POST",
                body: formData,
              }
            );
            if (response.ok) {
              const imageBlob = await response.blob();
              const imageUrl = URL.createObjectURL(imageBlob);
              setChromaImage(imageUrl);
              setPrediction(null);
              setAudioSrc(URL.createObjectURL(file));
              setUploadedFile(file);
            } else {
              console.error("Failed to analyze chroma");
            }
          }
        } catch (error) {
          console.error("Error uploading file:", error);
        }
      };
    }
  };

  return (
    <div className="app">
      <h1>Chordify Audio Processor</h1>
      <div className="controls">
        <button onClick={isRecording ? stopRecording : startRecording}>
          {isRecording ? "Stop Recording" : "Start Recording"}
        </button>
        <div className="upload-section">
          <label htmlFor="file-upload" className="upload-button">
            Upload Audio File
          </label>
          <input
            id="file-upload"
            type="file"
            accept="audio/*"
            onChange={handleFileUpload}
            style={{ display: "none" }}
          />
        </div>
      </div>
      <div className="results">
        {prediction && (
          <div className="result">
            <h2>Prediction Result</h2>
            <p>Chord: {prediction.chord}</p>
            <p>Confidence: {prediction.confidence}%</p>
          </div>
        )}
        {chromaImage && (
          <div className="chroma-result">
            {audioSrc && (
              <audio controls src={audioSrc} className="audio-player" />
            )}
            <img src={chromaImage} alt="Chroma Analysis" />
            <div className="slicing-controls">
              <h3>Slice Audio</h3>
              <div className="slice-inputs">
                <div className="input-group">
                  <label>Start Time (s):</label>
                  <input
                    type="number"
                    min="0"
                    step="0.1"
                    placeholder="0.0"
                    value={startTime}
                    onChange={(e) => setStartTime(e.target.value)}
                  />
                </div>
                <div className="input-group">
                  <label>End Time (s):</label>
                  <input
                    type="number"
                    min="0"
                    step="0.1"
                    placeholder="4.5"
                    value={endTime}
                    onChange={(e) => setEndTime(e.target.value)}
                  />
                </div>
                <button
                  onClick={() => {
                    const start = parseFloat(startTime);
                    const end = parseFloat(endTime);
                    if (!isNaN(start) && !isNaN(end) && start < end) {
                      setSlices([...slices, { start, end }]);
                      setStartTime("");
                      setEndTime("");
                    }
                  }}
                >
                  Add Slice
                </button>
              </div>
              <div className="slices-list">
                {slices.map((slice, index) => (
                  <div key={index} className="slice-item">
                    <span>
                      Slice {index + 1}: {slice.start}s - {slice.end}s
                    </span>
                    <button
                      className="remove-slice-btn"
                      onClick={() =>
                        setSlices(slices.filter((_, i) => i !== index))
                      }
                    >
                      Remove
                    </button>
                  </div>
                ))}
                {slices.length > 0 && (
                  <div style={{ marginTop: 12 }}>
                    <button
                      onClick={async () => {
                        // choose file: prefer uploaded file, then recorded blob
                        const fileToSend = uploadedFile || audioBlob;
                        if (!fileToSend) {
                          alert('No audio file available. Please upload or record audio first.');
                          return;
                        }

                        const formData = new FormData();
                        // if it's a File object it has a name, if it's a Blob use a default name
                        const filename = fileToSend.name ? fileToSend.name : 'recording.webm';
                        formData.append('file', fileToSend, filename);
                        const timestampsPayload = slices.map(s => ({ start: s.start.toString(), end: s.end.toString() }));
                        formData.append('timestamps', JSON.stringify(timestampsPayload));

                        try {
                          const res = await fetch('http://localhost:8000/predict_time_stamps', {
                            method: 'POST',
                            body: formData,
                          });
                          if (res.ok) {
                            const json = await res.json();
                            // store detailed slice results (start,end,chord,confidence)
                            setSliceResults(json.segments || []);
                            setSlices([]);
                            setPrediction(null);
                            console.log('Slices response:', json);
                          } else {
                            const txt = await res.text();
                            console.error('Server error:', txt);
                            alert('Server returned an error: ' + res.status);
                          }
                        } catch (err) {
                          console.error('Request failed', err);
                          alert('Request failed: ' + err.message);
                        }
                      }}
                    >
                      Submit Slices
                    </button>
                  </div>
                )}
              </div>

              {sliceResults.length > 0 && (
                <div className="slice-results" style={{ marginTop: 12 }}>
                  <h3>Slice Predictions</h3>
                  {sliceResults.map((r, i) => (
                    <div key={i} className="slice-prediction-item">
                      <strong>Slice {i + 1}:</strong> {r.start}s - {r.end}s —
                      <span style={{ marginLeft: 8 }}><strong>Chord:</strong> {r.chord}</span>
                      <span style={{ marginLeft: 8 }}><strong>Confidence:</strong> {r.confidence}%</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

export default App;
