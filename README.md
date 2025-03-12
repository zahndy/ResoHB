# This repository has two branches


# ResoHB 
Client that sends your heartbeat to a websocket server (WebSocketForNeos)
which can then be connected to from Resonite over localhost.
This is a drop-in replacement for LynxVR.

setup:
- adb pair the watch
- download [ResoHB.apk](https://github.com/zahndy/ResoHB/releases/download/1.2.0/ResoHB_1.2.0.apk)
- install the app with adb install ResoHB.apk
- give the app permissions and turn off Pause App Activity, you will find Permissions after long pressing on the app in the app list
- open the app
- enter the address of the computer running WebSocketForNeos
- hit start and it should connect to WebSocketForNeos

# ResoHBS 
Server where clients like Resonite can directly connect to the watch.

setup:
- Set a Static IP in your router for your smartwatch
- adb pair the watch
- download [ResoHBS.apk](https://github.com/zahndy/ResoHB/releases/download/1.2.0/ResoHBS_1.2.0.apk)
- install the app with adb install ResoHBS.apk
- give the app permissions and turn off Pause App Activity, you will find Permissions after long pressing on the app in the app list
- open the app 
- hit start and it should show you the address to connect to
- In Resonite set the WebsocketClient URL to the address displayed on the watch
- it might take a while to connect based on your watch's connectivity 
