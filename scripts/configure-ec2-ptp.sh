#!/usr/bin/env bash
set -euo pipefail

PTP_DEVICE="${EC2_PTP_DEVICE:-/dev/ptp_ena}"
MODPROBE_FILE="/etc/modprobe.d/ena-phc.conf"
UDEV_FILE="/etc/udev/rules.d/53-ec2-network-interfaces.rules"
CHRONY_FILE="/etc/chrony.conf"
CHRONY_LINE="refclock PHC ${PTP_DEVICE} poll 0 delay 0.000010 prefer"

if ! command -v modinfo >/dev/null 2>&1 || ! modinfo ena >/dev/null 2>&1; then
  echo "ENA driver is not present; skipping EC2 PTP setup."
  exit 0
fi

ENA_VERSION="$(modinfo ena | awk -F': *' '$1 == "version" {print $2; exit}')"
echo "Detected ENA driver ${ENA_VERSION:-unknown}."

if [ -f /sys/module/ena/parameters/phc_enable ]; then
  echo "Current ena.phc_enable=$(cat /sys/module/ena/parameters/phc_enable)."
fi

echo "options ena phc_enable=1" | sudo tee "$MODPROBE_FILE" >/dev/null
echo "Wrote $MODPROBE_FILE. A reboot or ENA module reload is required if phc_enable is currently 0."

if ! sudo grep -F 'SYMLINK += "ptp_ena"' "$UDEV_FILE" >/dev/null 2>&1; then
  echo 'SUBSYSTEM=="ptp", ATTR{clock_name}=="ena-ptp-*", SYMLINK += "ptp_ena"' | sudo tee -a "$UDEV_FILE" >/dev/null
fi
sudo udevadm control --reload-rules || true
sudo udevadm trigger || true

if [ -e "$PTP_DEVICE" ]; then
  if ! sudo grep -F "$CHRONY_LINE" "$CHRONY_FILE" >/dev/null 2>&1; then
    echo "$CHRONY_LINE" | sudo tee -a "$CHRONY_FILE" >/dev/null
  fi
  sudo systemctl restart chronyd
  echo "Configured chrony to use $PTP_DEVICE."
  chronyc sources || true
else
  echo "$PTP_DEVICE is not exposed yet. Leaving host configured for PHC on next boot if the instance family supports it."
fi
