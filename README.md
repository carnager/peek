# peek

<img src="docs/brand/peek-icon.svg" width="96" align="right" alt="peek">

Gallery for Android, and the phone app for [trunk](https://github.com/carnager/trunk), a
self-hosted photo and video server.

- Photos on the phone and on trunk in one timeline.
- The evening review: go through what's new, pick what goes to the trash, then what goes to trunk.
  Nothing is deleted or uploaded without it.
- Uploads in the background, resumable, and skips what trunk already has. Optional size limit for
  photos.
- Albums, favourites, trash, memories, people, public links, sharing within the household.
- Search by place, date and name, and by what's in the photo when trunk runs `trunk-ai`.
- Cleanup of look-alikes and bursts on trunk: keep the best, the rest goes to trunk's trash.
- Editor: crop, filters, colour, drawing and text.

Without trunk, peek is a plain gallery for the photos on the phone.

Needs Android 11+.

## Build

```sh
./gradlew assembleRelease
```

Release signing is read from `~/.local/android/release-keys/peek.properties`, or from the
`PEEK_ANDROID_*` environment variables (see `app/build.gradle.kts`). Without it, the build is
signed with the debug key.

Fonts in `app/src/main/assets/fonts` are under the SIL Open Font License or Apache 2.0.
