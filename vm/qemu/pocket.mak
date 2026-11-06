# Pocket Linux の VM で使う機器だけ（build-qemu.sh が configs/devices/aarch64-softmmu/ に置く）。
# virt マシンが選ぶもの（GIC・PL031・virtio-mmio など）は Kconfig が自動で入れる。
CONFIG_ARM_VIRT=y
CONFIG_VIRTIO_BLK=y
CONFIG_VIRTIO_NET=y
CONFIG_VIRTIO_SERIAL=y
CONFIG_VIRTIO_RNG=y
CONFIG_VIRTIO_BALLOON=y
CONFIG_VIRTIO_9P=y
