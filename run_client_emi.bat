@rem Dev client loading EMI instead of the default JEI viewer - see the -Pemi branch in build.gradle.
@rem The AE2 JEI bridge is not loaded here: its mod metadata requires JEI.
@gradlew runClient -Pemi --no-configuration-cache
