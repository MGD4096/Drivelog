# EntryNav2 build image: armhf cross GCC 4.6 against eglibc 2.15, the C library of the EntryNav2 head unit.
#   docker build -t drivelog-entrynav2-tc headunit/toolchain
# (Same content as the image other EntryNav2 plugin projects use: if gauge-entrynav2-tc exists, run.sh uses that.)
FROM ubuntu:12.04
RUN sed -i -e 's#http://[a-z.]*ubuntu.com#http://old-releases.ubuntu.com#g' /etc/apt/sources.list \
 && apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --force-yes \
      gcc-arm-linux-gnueabihf g++-arm-linux-gnueabihf binutils-arm-linux-gnueabihf make file \
 && apt-get clean
WORKDIR /work
