# Drivelog bridge - the head-unit plugin (libDrivelogBridge.so) for NBT Evo and EntryNav2.
#   make entrynav2-dist   EntryNav2: dist/drivelog-bridge-entrynav2.tar          (toolchain/run.sh tc make entrynav2-dist)
#   make nbt-dist         NBT Evo:   dist/drivelog-bridge-nbt.tar               (needs qcc, QNX SDP 6.5; or build_nbt.bat)
#   make db               the two zips (python3)                                 (toolchain/run.sh runner make db)
#   make test-build       the host test, built for ARM like the car             (toolchain/check.sh runs everything)
#
# The car's libAppFramework.A.so / libCoreFramework.A.so are BMW's files, not part of this project (git-ignored):
#   lib/entrynav2/   from /opt/hmi/lib          lib/nbt/   from /opt/hmi/ID5/lib

NAME        = DrivelogBridge
SRC         = src/drivelog_bridge.cpp
HDR         = src/bridge_core.hpp
PLUGIN_FILES = $(NAME).manifest $(NAME).xml $(NAME)_ALL_Texts.zip $(NAME)_ALL_Images.zip

# ---- EntryNav2: Linux ARMv7 hard-float, eglibc 2.15 (toolchain/Dockerfile) ------------------------------------------
EN2_CXX     ?= arm-linux-gnueabihf-g++
EN2_LIBDIR  ?= lib/entrynav2
EN2_LIBS     = $(wildcard $(EN2_LIBDIR)/libAppFramework.A.so $(EN2_LIBDIR)/libCoreFramework.A.so)
ARM_FLAGS    = -std=gnu++98 -O2 -Wall -Wextra -march=armv7-a -mfpu=neon -mfloat-abi=hard -fPIC
EN2_CXXFLAGS = $(ARM_FLAGS) -fvisibility=hidden -fvisibility-inlines-hidden -DBRIDGE_ENTRYNAV2 -Isrc
EN2_OUT      = build/entrynav2
EN2_STAGE    = build/stage-entrynav2
EN2_PLUGIN   = opt/hmi/data/ro/Plugins/$(NAME)

# ---- NBT Evo: QNX 6.5 ARMv7 -------------------------------------------------------------------------------------
QCC         ?= qcc
NBT_LIBDIR  ?= lib/nbt
NBT_LIBS     = $(wildcard $(NBT_LIBDIR)/libAppFramework.A.so $(NBT_LIBDIR)/libCoreFramework.A.so)
NBT_OUT      = build/nbt
NBT_STAGE    = build/stage-nbt
NBT_PLUGIN   = opt/hmi/ID5/data/ro/Plugins/$(NAME)

# ---- host test (ARM build against tests/fake_framework.cpp, run under qemu-arm) ----------------------------------
TEST_OUT     = build/test
TEST_DATA    = /tmp/drivelog-test

.PHONY: all entrynav2 entrynav2-dist nbt nbt-dist db test-build clean
all: entrynav2

entrynav2: $(EN2_OUT)/lib$(NAME).so

$(EN2_OUT)/lib$(NAME).so: $(SRC) $(HDR)
	@test -n "$(EN2_LIBS)" || { echo "copy libAppFramework.A.so and libCoreFramework.A.so of the EntryNav2 (/opt/hmi/lib) into $(EN2_LIBDIR)"; exit 1; }
	@mkdir -p $(EN2_OUT)
	$(EN2_CXX) $(EN2_CXXFLAGS) -shared -Wl,-soname,lib$(NAME).so -o $@ $(SRC) \
	    -Wl,--allow-shlib-undefined -L$(EN2_LIBDIR) -Wl,-rpath-link,$(EN2_LIBDIR) \
	    -l:libAppFramework.A.so -l:libCoreFramework.A.so -lpthread -ldl -lrt
	@! arm-linux-gnueabihf-objdump -T $@ | grep -E 'GLIBC_2\.(1[6-9]|[2-9][0-9])' || { echo "needs a newer glibc than the car's 2.15"; exit 1; }

nbt: $(NBT_OUT)/lib$(NAME).so

$(NBT_OUT)/lib$(NAME).so: $(SRC) $(HDR)
	@test -n "$(NBT_LIBS)" || { echo "copy libAppFramework.A.so and libCoreFramework.A.so of the NBT Evo (/opt/hmi/ID5/lib) into $(NBT_LIBDIR)"; exit 1; }
	@mkdir -p $(NBT_OUT)
	$(QCC) -Vgcc_ntoarmv7le_gpp -O2 -Wall -D_QNX_SOURCE -fPIC -Isrc -shared -Wl,-soname,lib$(NAME).so -o $@ $(SRC) \
	    $(NBT_LIBS) -Wl,--allow-shlib-undefined -lsocket -lang-c++

# The archives mirror the head unit's file system: unpack in /tmp on the car and copy opt/ over / (README.md).
define stage
	rm -rf $(1)
	mkdir -p $(1)/$(2)
	cp $(3) $(PLUGIN_FILES) $(1)/$(2)/
	cp install/install.sh install/remove.sh $(1)/
	chmod 755 $(1)/$(2)/lib$(NAME).so $(1)/install.sh $(1)/remove.sh
	cd $(1) && md5sum $(2)/* > MD5SUMS
	mkdir -p dist
	tar -cf $(4) -C $(1) MD5SUMS install.sh remove.sh opt
	@echo [DONE] $(4)
endef

entrynav2-dist: $(EN2_OUT)/lib$(NAME).so $(PLUGIN_FILES) install/install.sh install/remove.sh
	$(call stage,$(EN2_STAGE),$(EN2_PLUGIN),$<,dist/drivelog-bridge-entrynav2.tar)

nbt-dist: $(NBT_OUT)/lib$(NAME).so $(PLUGIN_FILES) install/install.sh install/remove.sh
	$(call stage,$(NBT_STAGE),$(NBT_PLUGIN),$<,dist/drivelog-bridge-nbt.tar)

db:
	python3 make_db.py

# The plugin for EntryNav2 with its data folder in build/test/data, and the test that loads it. Same compiler and
# flags as for the car, so the test runs the code the car runs (apart from the framework, which is faked).
test-build: $(TEST_OUT)/lib$(NAME).so $(TEST_OUT)/host_test

$(TEST_OUT)/lib$(NAME).so: $(SRC) $(HDR)
	@mkdir -p $(TEST_OUT)
	$(EN2_CXX) $(ARM_FLAGS) -DBRIDGE_ENTRYNAV2 -Isrc -DBRIDGE_DATA_DIR='"$(TEST_DATA)"' \
	    -DBRIDGE_PLUGIN_DIR='"$(TEST_DATA)/plugin"' -shared -o $@ $(SRC) -lpthread -ldl -lrt

$(TEST_OUT)/host_test: tests/host_test.cpp tests/fake_framework.cpp tests/fake_framework.hpp $(HDR)
	@mkdir -p $(TEST_OUT)
	$(EN2_CXX) $(ARM_FLAGS) -rdynamic -Isrc -Itests -o $@ tests/host_test.cpp tests/fake_framework.cpp \
	    -lpthread -ldl -lrt

clean:
	rm -rf build dist
