# temi LAN Admin Panel

The app now starts a small HTTP admin panel on the temi tablet at port **8080**.

Open:

`http://<temi-LAN-IP>:8080/`

Features:
- Live MJPEG camera feed when temi exposes a usable Android camera to third-party apps.
- Device camera barcode scanner: use your phone, tablet, or laptop's camera directly in the browser to scan book barcodes (ISBN / library barcodes) with live audio/visual feedback, auto-look up book titles and authors from Finna, and dispatch searches or direct book records to the robot screen.
- Manual book & code search: search by title, author, or barcode number directly from the admin panel to the robot screen.
- Temi camera scanner: optionally toggle the robot's built-in camera scanner.
- WASD keyboard driving.
- Touch controls for phones/tablets.
- STOP button.
- Short server-side command timeout so movement stops if the browser/client disappears.
- `skidJoy(..., smart=true)` is used for manual movement, so temi's smart movement/obstacle handling remains enabled.

The panel is intentionally LAN-only and has no Internet/cloud dependency.

## Camera note

temi's public SDK does not provide a general-purpose camera-frame streaming API; its documented live video is tied to video calls. The implementation therefore tries the Android Camera2 interface on temi V3. If the installed temi firmware reserves the built-in camera from third-party Camera2 access, the admin panel will still work for driving but will show the camera as unavailable.

The app requests the Android `CAMERA` permission on first launch.
