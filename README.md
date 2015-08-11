# Android Bluetooth LE Demo

Bluetooth Low Energy is a special version of Bluetooth that allows wireless communication with devices using very little power, which makes it suitable for mobile phones as well as battery powered devices such as beacons, heart rate monitors and other sensors. It has been available in Android since 4.3 (Jelly Bean).

This project demonstrates how to communicate with a device using Bluetooth LE. It is set up as a working app project that you can import into Android Studio or compile using Gradle from the command line. It covers searching for nearby devices, sending data and receiving updates from the remote device.

### Running the project

Some UUIDs of services and characteristics are hard-coded in the project. If you know the UUIDs of the device you're testing with you should [alter those values](https://github.com/Pixplicity/android-bluetooth-demo/blob/master/app/src/main/java/com/pixplicity/bluetoothdemo/MainActivity.java#L45) before running the project.

### In this project

This app covers the following topics:

- Checking if Bluetooth is enabled and requesting the user to enabled it if needed
- Starting a search for Bluetooth devices
- Connecting to a device of interest
- Sending a byte to the remote device
- Receiving updates whenever a value on the remote device changes.

Note that we use the Jelly Bean (4.3) APIs for communication, even though those are deprecated in Lollipop (5.0), because the new APIs are only available from 5.0 and up.
