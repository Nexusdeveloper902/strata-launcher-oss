# Benchmark build type: same shrinking and optimisation as release, plus what the Macrobenchmark harness needs.
# Keep the original names so a Baseline Profile collected from this build (BaselineProfileGenerator in :benchmark)
# names real classes and methods and can be committed as app/src/main/baseline-prof.txt. The release build then
# rewrites the profile with its own R8 mapping. Obfuscation does not change run time, so the timings still hold.
-dontobfuscate
