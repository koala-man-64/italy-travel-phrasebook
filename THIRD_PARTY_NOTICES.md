# Offline conversation third-party notices

The Android conversation feature uses the components below. The app includes
[third-party-licenses.txt](third-party-licenses.txt) so the Apache License 2.0
text and component notices remain available without a network connection.

| Component | Version or model | Terms and provenance |
| --- | --- | --- |
| Vosk Android | `com.alphacephei:vosk-android:0.3.75` | [Vosk source license](https://github.com/alphacep/vosk-api/blob/master/COPYING), Apache License 2.0. The inspected Android AAR and its `classes.jar` contain no separate `NOTICE` file. |
| Java Native Access | `net.java.dev.jna:jna:5.18.1@aar` | [JNA 5.18.1 LICENSE](https://github.com/java-native-access/jna/blob/5.18.1/LICENSE) offers LGPL 2.1 or later **or** Apache License 2.0. This distribution uses the Apache License 2.0 option. The AAR's `classes.jar` contains `META-INF/LICENSE` and `META-INF/AL2.0`, with no separate `NOTICE` file. |
| Vosk English model | `vosk-model-small-en-us-0.15` | [Official model catalog](https://alphacephei.com/vosk/models), Apache License 2.0. The downloaded ZIP's `README` states `Copyright 2020 Alpha Cephei Inc`; that notice is retained in the bundled license text. |
| Vosk Italian model | `vosk-model-small-it-0.22` | [Official model catalog](https://alphacephei.com/vosk/models), Apache License 2.0. The downloaded ZIP's `README` has no copyright line. |
| ML Kit on-device Translation | `com.google.mlkit:translate:17.0.3` | Governed by [Google's ML Kit terms and privacy information](https://developers.google.com/ml-kit/terms) and [translation usage guidelines](https://developers.google.com/ml-kit/language/translation/translation-terms), rather than the Apache License text in this bundle. Google Translate attribution is displayed beside translation results in the app. |

The Apache License text in the bundled file is the [official Apache License
2.0 text](https://www.apache.org/licenses/LICENSE-2.0.txt). The two model
archives are downloaded during the one-time setup and are not embedded in the
APK. Their exact versions are pinned by SHA-256 in the Android installer.

Google states that ML Kit processes translation input and output on device.
The SDK may contact Google for model or compatibility updates and sends usage
and performance metrics when connected; see the [ML Kit privacy
terms](https://developers.google.com/ml-kit/terms). The Google Translate badge
is from [Google's official attribution package](https://docs.cloud.google.com/translate/attribution)
and is displayed under its branding guidelines.
