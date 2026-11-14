# Strata Launcher R8 rules (release and the release-like `benchmark` build type; T-CI-07, docs/impl-notes/M9.md).
# Manual DI, no reflection in our own code (ARCHITECTURE §7). Every rule below names the reflection it protects.

# No renaming (M9 decision, Proposed): the source is public, so obfuscation hides nothing, and it costs three things.
# 1. The diagnostic log keeps Strata's own stack frames readable (`app.strata.` frames, LogRedaction, M8 part 3);
#    with renaming they would be meaningless without a per-release mapping file.
# 2. A Baseline Profile collected on the `benchmark` build names real classes and applies unchanged.
# 3. One less way for reflection-by-name to break (see protobuf below).
# Shrinking and optimisation stay on; the size cost of keeping names is about 66 KB (docs/perf/optimization-pass-1.md).
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# protobuf-javalite (settings and theme index DataStore, :core:data). The lite runtime finds message fields by name
# through reflection (MessageSchema.reflectField reads the names from newMessageInfo). protobuf-javalite ships no
# consumer rules, so if renaming is ever switched back on, R8 would rename `onboardingDone_` while the string stays,
# and the first settings read would throw "Field onboardingDone_ ... not found". checkReleaseApk also checks this.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
