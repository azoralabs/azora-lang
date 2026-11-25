#pragma once

#include <string>

namespace azora {

/** `azora __parse-job <job>`; see parse_job.cpp. Returns the exit status. */
int runParseJob(const std::string& jobPath);

}  // namespace azora
