FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_HOME=/opt/android-sdk
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless \
    wget \
    unzip \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/clt.zip && \
    unzip -q /tmp/clt.zip -d ${ANDROID_HOME}/cmdline-tools && \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/clt.zip

ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}

RUN yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager "platforms;android-35" "build-tools;35.0.0"

WORKDIR /project
COPY . .
COPY output/debug.keystore /root/.android/debug.keystore

RUN chmod +x gradlew && ./gradlew assembleDebug --no-daemon --stacktrace 2>&1 || (cat /root/.gradle/daemon/*/daemon-*.out.log 2>/dev/null; exit 1)

CMD ["sh", "-c", "cp app/build/outputs/apk/debug/app-debug.apk /output/pocket-talk.apk"]
