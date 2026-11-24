# peek

<img src="docs/brand/peek-icon.svg" width="96" align="right" alt="peek">

A gallery for Android. It works on its own with the photos and videos on your phone. If you
run [trunk](https://github.com/carnager/trunk), a self-hosted photo and video server, peek can
connect to it too, but it doesn't have to.

- Timeline, folders, albums, favourites, memories and trash for what's on the phone.
- The evening review: go through what's new and pick what goes to the trash.
- Editor: crop, filters, colour, drawing and text.

### With trunk (optional)

- Photos on the phone and on trunk in one timeline.
- A second review step: pick what goes to trunk. Nothing is uploaded without it.
- Uploads in the background, resumable, and skips what trunk already has. Optional size limit for
  photos.
- Albums on trunk, people, public links, sharing within the household.
- Search by place, date and name, and by what's in the photo when trunk runs `trunk-ai`.
- Cleanup of look-alikes and bursts on trunk: keep the best, the rest goes to trunk's trash.

Needs Android 11+.

## Build

```sh
./gradlew assembleRelease
```

Release signing is read from `~/.local/android/release-keys/peek.properties`, or from the
`PEEK_ANDROID_*` environment variables (see `app/build.gradle.kts`). Without it, the build is
signed with the debug key.

Fonts in `app/src/main/assets/fonts` are under the SIL Open Font License or Apache 2.0.
