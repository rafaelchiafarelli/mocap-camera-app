# eclipse-temurin:17-jdk-noble, pinned by digest (DEPENDENCIES.md rule B). Noble for Python 3.12, which
# mocap-contracts needs for the control loopback test.
FROM eclipse-temurin@sha256:fb79620c3f6ee8f1c7530a8dc4fd49e3b410bcef037694ad4858a1553bac4170

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/gradle/bin

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip wget usbutils python3 python3-venv \
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

# Python for the recorder side of the control loopback test: the generated mocap-contracts endpoints
# (from the submodule, on PYTHONPATH) need only these, installed from the hash-locked file.
COPY loopback-requirements.lock /tmp/
RUN python3 -m venv /opt/loopback \
 && /opt/loopback/bin/pip install --no-cache-dir --require-hashes -r /tmp/loopback-requirements.lock \
 && rm /tmp/loopback-requirements.lock

WORKDIR /work
