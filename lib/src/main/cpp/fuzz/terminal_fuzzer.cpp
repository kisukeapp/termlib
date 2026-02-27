/*
 * ConnectBot Terminal
 * Copyright 2025 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * libFuzzer harness for libvterm.
 *
 * Feeds arbitrary byte sequences into vterm_input_write() to exercise the
 * parser, state machine, and screen callbacks. This catches memory safety
 * issues (buffer overflows, use-after-free, etc.) in the C parsing code
 * that processes terminal escape sequences from untrusted remote hosts.
 *
 * Build (standalone, not part of the Android build):
 *   clang++ -g -O1 -fsanitize=fuzzer,address \
 *       -I ../libvterm/include \
 *       -DVTERM_STATIC \
 *       terminal_fuzzer.cpp \
 *       ../libvterm/src/encoding.c \
 *       ../libvterm/src/keyboard.c \
 *       ../libvterm/src/mouse.c \
 *       ../libvterm/src/parser.c \
 *       ../libvterm/src/pen.c \
 *       ../libvterm/src/screen.c \
 *       ../libvterm/src/state.c \
 *       ../libvterm/src/unicode.c \
 *       ../libvterm/src/vterm.c \
 *       -o terminal_fuzzer -lstdc++
 *
 * Run:
 *   ./terminal_fuzzer corpus/ -max_len=4096
 */

#include <cstdint>
#include <cstdlib>
#include <cstring>

#include <vterm.h>

// ---- Screen callbacks (no-op sinks) ----

static int fuzz_damage(VTermRect rect, void* user) {
    (void)rect; (void)user;
    return 1;
}

static int fuzz_moverect(VTermRect dest, VTermRect src, void* user) {
    (void)dest; (void)src; (void)user;
    return 1;
}

static int fuzz_movecursor(VTermPos pos, VTermPos oldpos, int visible, void* user) {
    (void)pos; (void)oldpos; (void)visible; (void)user;
    return 1;
}

static int fuzz_settermprop(VTermProp prop, VTermValue* val, void* user) {
    (void)prop; (void)val; (void)user;
    return 1;
}

static int fuzz_bell(void* user) {
    (void)user;
    return 1;
}

static int fuzz_sb_pushline(int cols, const VTermScreenCell* cells, int continuation, void* user) {
    (void)cols; (void)cells; (void)continuation; (void)user;
    return 1;
}

static int fuzz_sb_popline(int cols, VTermScreenCell* cells, int* continuation, void* user) {
    (void)cols; (void)cells; (void)continuation; (void)user;
    return 0; // no scrollback to pop
}

static const VTermScreenCallbacks screen_callbacks = {
    .damage      = fuzz_damage,
    .moverect    = fuzz_moverect,
    .movecursor  = fuzz_movecursor,
    .settermprop = fuzz_settermprop,
    .bell        = fuzz_bell,
    .resize      = nullptr,
    .sb_pushline = fuzz_sb_pushline,
    .sb_popline  = fuzz_sb_popline,
};

// ---- State fallback callbacks (exercise OSC/CSI/DCS/APC paths) ----

static int fuzz_osc(int command, VTermStringFragment frag, void* user) {
    (void)command; (void)frag; (void)user;
    return 0; // not handled, let libvterm continue
}

static int fuzz_csi(const char* leader, const long args[], int argcount,
                    const char* intermed, char command, void* user) {
    (void)leader; (void)args; (void)argcount;
    (void)intermed; (void)command; (void)user;
    return 0;
}

static int fuzz_dcs(const char* command, size_t commandlen,
                    VTermStringFragment frag, void* user) {
    (void)command; (void)commandlen; (void)frag; (void)user;
    return 0;
}

static int fuzz_apc(VTermStringFragment frag, void* user) {
    (void)frag; (void)user;
    return 0;
}

static const VTermStateFallbacks state_fallbacks = {
    .control  = nullptr,
    .csi      = fuzz_csi,
    .osc      = fuzz_osc,
    .dcs      = fuzz_dcs,
    .apc      = fuzz_apc,
};

// ---- Output callback (keyboard output sink) ----

static void fuzz_output(const char* s, size_t len, void* user) {
    (void)s; (void)len; (void)user;
}

// ---- Selection callbacks ----

static int fuzz_selection_set(VTermSelectionMask mask, VTermStringFragment frag, void* user) {
    (void)mask; (void)frag; (void)user;
    return 1;
}

static int fuzz_selection_query(VTermSelectionMask mask, void* user) {
    (void)mask; (void)user;
    return 0;
}

static const VTermSelectionCallbacks selection_callbacks = {
    .set   = fuzz_selection_set,
    .query = fuzz_selection_query,
};

// ---- Fuzzer entry point ----

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    if (size < 2) return 0;

    // Use first two bytes to select terminal dimensions (bounded).
    // This exercises resize-related paths with different geometries.
    int rows = (data[0] % 64) + 1;  // 1..64
    int cols = (data[1] % 200) + 1; // 1..200
    const uint8_t* input = data + 2;
    size_t input_len = size - 2;

    VTerm* vt = vterm_new(rows, cols);
    if (!vt) return 0;

    vterm_set_utf8(vt, 1);
    vterm_output_set_callback(vt, fuzz_output, nullptr);

    VTermScreen* vts = vterm_obtain_screen(vt);
    vterm_screen_set_callbacks(vts, &screen_callbacks, nullptr);
    vterm_screen_enable_altscreen(vts, 1);
    vterm_screen_reset(vts, 1);

    // Wire state fallbacks for OSC/CSI/DCS/APC
    VTermState* state = vterm_obtain_state(vt);
    vterm_state_set_unrecognised_fallbacks(state, &state_fallbacks, nullptr);

    // Wire selection callbacks for OSC 52
    char selection_buf[4096];
    vterm_state_set_selection_callbacks(state, &selection_callbacks, nullptr,
                                       selection_buf, sizeof(selection_buf));

    // Feed the fuzzed input
    vterm_input_write(vt, reinterpret_cast<const char*>(input), input_len);

    // Exercise a resize mid-stream if there is enough data.
    // This tests the interaction between parser state and geometry changes.
    if (input_len > 4) {
        int new_rows = (input[input_len - 2] % 48) + 1;
        int new_cols = (input[input_len - 1] % 160) + 1;
        vterm_set_size(vt, new_rows, new_cols);
    }

    // Read back some cells to exercise the screen query path.
    VTermPos pos = {0, 0};
    VTermScreenCell cell;
    vterm_screen_get_cell(vts, pos, &cell);

    vterm_free(vt);
    return 0;
}
