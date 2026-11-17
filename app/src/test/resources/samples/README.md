# Real handwriting samples

Drop `sample_*.json` files here and run `./gradlew test`: `RealSamplesTest` scores the recogniser on them and prints every misread.

Collect them on the phone: write an expression, tap it with the Select tool, choose **Edit**, type what you meant,
tick *Save this handwriting as a sample*, **Apply**. Then:

    adb pull /sdcard/Android/data/com.bsoft.bhandbook/files/samples/ app/src/test/resources/

Samples never leave the device unless you pull them. Aim for 5+ writings of each digit and operator, in your normal style.
