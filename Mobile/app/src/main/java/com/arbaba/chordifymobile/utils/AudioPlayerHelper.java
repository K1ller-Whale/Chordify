package com.arbaba.chordifymobile.utils;

import android.media.MediaPlayer;
import android.util.Log;
import java.io.File;
import java.io.IOException;

public class AudioPlayerHelper {
    private MediaPlayer mediaPlayer;
    private boolean isPlaying = false;
    private OnPlaybackCompleteListener currentListener;

    public void playAudio(File audioFile, OnPlaybackCompleteListener listener) {
        // Always stop and release any existing player first
        stopAudio();

        if (audioFile == null || !audioFile.exists()) {
            Log.e("AudioPlayerHelper", "Audio file does not exist: " + (audioFile != null ? audioFile.getAbsolutePath() : "null"));
            if (listener != null) {
                listener.onError("Audio file not found");
            }
            return;
        }

        try {
            mediaPlayer = new MediaPlayer();
            currentListener = listener;
            
            mediaPlayer.setDataSource(audioFile.getAbsolutePath());
            mediaPlayer.prepare();
            
            mediaPlayer.setOnCompletionListener(mp -> {
                isPlaying = false;
                if (currentListener != null) {
                    currentListener.onComplete();
                }
                releasePlayer();
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e("AudioPlayerHelper", "MediaPlayer error: what=" + what + ", extra=" + extra);
                isPlaying = false;
                if (currentListener != null) {
                    currentListener.onError("Playback error: " + what);
                }
                releasePlayer();
                return true; // Error handled
            });

            mediaPlayer.start();
            isPlaying = true;
            Log.d("AudioPlayerHelper", "Started playing: " + audioFile.getAbsolutePath());

        } catch (IOException e) {
            Log.e("AudioPlayerHelper", "Error playing audio", e);
            isPlaying = false;
            releasePlayer();
            if (listener != null) {
                listener.onError("Failed to play audio: " + e.getMessage());
            }
        } catch (IllegalStateException e) {
            Log.e("AudioPlayerHelper", "Illegal state error", e);
            isPlaying = false;
            releasePlayer();
            if (listener != null) {
                listener.onError("Player state error: " + e.getMessage());
            }
        }
    }

    public void stopAudio() {
        if (mediaPlayer != null) {
            try {
                if (isPlaying) {
                    mediaPlayer.stop();
                }
            } catch (IllegalStateException e) {
                Log.e("AudioPlayerHelper", "Error stopping audio (illegal state)", e);
            } catch (Exception e) {
                Log.e("AudioPlayerHelper", "Error stopping audio", e);
            }
            releasePlayer();
        }
        isPlaying = false;
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e("AudioPlayerHelper", "Error releasing MediaPlayer", e);
            }
            mediaPlayer = null;
        }
        currentListener = null;
    }

    public boolean isPlaying() {
        return isPlaying && mediaPlayer != null;
    }
    
    public void pause() {
        if (mediaPlayer != null && isPlaying) {
            try {
                mediaPlayer.pause();
                isPlaying = false;
            } catch (IllegalStateException e) {
                Log.e("AudioPlayerHelper", "Error pausing audio", e);
                stopAudio();
            }
        }
    }

    public interface OnPlaybackCompleteListener {
        void onComplete();
        void onError(String error);
    }
}
