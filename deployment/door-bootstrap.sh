#!/bin/sh
# The boot coordinator stages this file only after making a full private SD backup.
exec /usr/bin/python3 /boot/firmware/door-payload/deployment/bootstrap_camera.py
