#include "test_support.hpp"

namespace test {

std::vector<Case>& registry() {
    static std::vector<Case> cases;
    return cases;
}

int& failures() {
    static int count = 0;
    return count;
}

void fail(const char* file, int line, const std::string& message) {
    ++failures();
    std::cerr << "  FAIL " << file << ":" << line << ": " << message << "\n";
}

}  // namespace test

int main() {
    int failedCases = 0;
    for (const test::Case& item : test::registry()) {
        const int before = test::failures();
        try {
            item.body();
        } catch (const std::exception& error) {
            test::fail(__FILE__, __LINE__, std::string("uncaught exception: ") + error.what());
        }
        const bool passed = test::failures() == before;
        if (!passed) ++failedCases;
        std::cout << (passed ? "PASS  " : "FAIL  ") << item.name << "\n";
    }
    std::cout << "\n" << test::registry().size() - static_cast<std::size_t>(failedCases) << " of "
              << test::registry().size() << " cases passed\n";
    return failedCases == 0 ? 0 : 1;
}
