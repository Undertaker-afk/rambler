// Desktop check of the exact engine code used on Android:
//   engine_test <model.gguf> <prefix.txt> <suffix.txt> [<suffix2.txt> ...]
// Runs the first suffix cold, then with the cached prefix state, and verifies identical output.
#include <chrono>
#include <cstdio>
#include <fstream>
#include <sstream>

#include "engine.h"

static std::string slurp(const char *p) { std::ifstream f(p); std::stringstream s; s << f.rdbuf(); return s.str(); }
static double ms(std::chrono::steady_clock::time_point a) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - a).count();
}

int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "usage\n"); return 2; }
    rambler::Engine *e = rambler::load(argv[1], 4);
    if (!e) return 1;
    std::string prefix = slurp(argv[2]);
    int rc = 0;
    std::string first_out;
    for (int i = 3; i < argc; i++) {
        std::string suffix = slurp(argv[i]), out;
        bool cached = false;
        auto t = std::chrono::steady_clock::now();
        if (!rambler::generate(e, prefix, suffix, 120, out, &cached)) { printf("FAIL %s\n", argv[i]); rc = 1; continue; }
        printf("[%s %.0f ms] %s\n", cached ? "cached-prefix" : "cold-prefix  ", ms(t), out.c_str());
        if (i == 3) first_out = out;
    }
    // Same request again must give the identical answer from the cached prefix.
    std::string again; bool cached = false;
    rambler::generate(e, prefix, slurp(argv[3]), 120, again, &cached);
    printf("repeat of first: cached=%d identical=%d\n", cached, again == first_out);
    if (!cached || again != first_out) rc = 1;
    rambler::release(e);
    return rc;
}
