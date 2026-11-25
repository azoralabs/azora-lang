# The only build description of the C++ compiler (CPP_PORT_PLAN.md §5).
# clang++ and /usr/bin/make from the Xcode command-line tools; nothing else.
#
#   make BUILD=release   build/release/azora   (-O2)
#   make BUILD=debug     build/debug/azora     (-O0 -g)
#   make BUILD=asan      build/asan/azora      (debug + address/undefined sanitizers)

BUILD ?= release
CXX := /usr/bin/clang++

SOURCES := $(shell find src -name '*.cpp' | sort)
OUT := build/$(BUILD)
OBJECTS := $(patsubst src/%.cpp,$(OUT)/obj/%.o,$(SOURCES))
BINARY := $(OUT)/azora

CXXFLAGS := -std=c++20 -Wall -Wextra -Werror -fno-rtti -fwrapv -Isrc -MMD -MP

ifeq ($(BUILD),release)
CXXFLAGS += -O2
else ifeq ($(BUILD),debug)
CXXFLAGS += -O0 -g
else ifeq ($(BUILD),asan)
CXXFLAGS += -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer
LDFLAGS += -fsanitize=address,undefined
else
$(error BUILD must be release, debug or asan)
endif

$(BINARY): $(OBJECTS)
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) $(LDFLAGS) $(OBJECTS) -o $@

$(OUT)/obj/%.o: src/%.cpp
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) -c $< -o $@

.PHONY: clean
clean:
	rm -rf build/release build/debug build/asan

-include $(OBJECTS:.o=.d)
