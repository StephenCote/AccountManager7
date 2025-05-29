import cv2
import face_recognition
from deepface import DeepFace

def main():
    # Open a connection to the webcam
    video_capture = cv2.VideoCapture(0)

    while True:
        # Capture a single frame of video
        ret, frame = video_capture.read()

        # Convert the image from BGR color (OpenCV uses) to RGB color (face_recognition uses)
        rgb_frame = frame[:, :, ::-1]

        # Find all the faces in the current frame of video
        face_locations = face_recognition.face_locations(rgb_frame)

        for face_location in face_locations:
            top, right, bottom, left = face_location

            # Extract the face from the frame
            face_image = frame[top:bottom, left:right]

            # Analyze the face with DeepFace
            try:
                analysis = DeepFace.analyze(face_image, actions=['age', 'gender', 'emotion'], enforce_detection=False)

                # Extract results
                age = analysis['age']
                gender = analysis['gender']
                dominant_emotion = analysis['dominant_emotion']

                # Create a text label with the results
                label = f"{gender}, {age}y, {dominant_emotion}"

                # Draw a box around the face and add label
                cv2.rectangle(frame, (left, top), (right, bottom), (0, 0, 255), 2)
                cv2.putText(frame, label, (left, top - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 0, 0), 1)
            except Exception as e:
                print(f"Analysis error: {e}")

        # Display the resulting image
        cv2.imshow('Video', frame)

        # Hit 'q' on the keyboard to quit!
        if cv2.waitKey(1) & 0xFF == ord('q'):
            break

    # Release the webcam and close windows
    video_capture.release()
    cv2.destroyAllWindows()

if __name__ == "__main__":
    main()