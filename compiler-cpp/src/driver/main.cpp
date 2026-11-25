// The `azora` command line. During the port it carries only the hidden
// commands the differential checks use (CPP_PORT_PLAN.md §8, §10); the
// azora.kexe command set arrives with stage 9.
#include "driver/parse_job.h"

#include <cstdio>
#include <string>

int main(int argc, char** argv) {
    if (argc >= 3 && std::string(argv[1]) == "__parse-job") return azora::runParseJob(argv[2]);
    std::fprintf(stderr, "usage: azora __parse-job <job>\n");
    return 2;
}
