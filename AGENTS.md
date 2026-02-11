- After finish coding, use the following command to build & install:

```shell
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk || true
```


