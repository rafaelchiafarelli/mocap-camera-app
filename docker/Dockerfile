# eclipse-temurin:17-jdk-jammy, pinned by digest (DEPENDENCIES.md rule B)
FROM eclipse-temurin@sha256:43f4431cc895d37ceb115e2e1f160545ec45caee9c1cb4246fc6bb879d58aeda

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/gradle/bin

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip wget usbutils \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p $ANDROID_HOME/cmdline-tools \
 && wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/tools.zip \
 && unzip -q /tmp/tools.zip -d /tmp \
 && mv /tmp/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/tools.zip

RUN yes | sdkmanager --licenses > /dev/null \
 && sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" > /dev/null \
 && chmod -R a+rX $ANDROID_HOME

RUN wget -q https://services.gradle.org/distributions/gradle-8.7-bin.zip -O /tmp/gradle.zip \
 && unzip -q /tmp/gradle.zip -d /opt \
 && mv /opt/gradle-8.7 /opt/gradle \
 && rm /tmp/gradle.zip

WORKDIR /work
