# Contributing

Thanks for helping out. Bug reports, new game IDs and small fixes are all welcome.

- **Bugs:** use the [bug report template](https://github.com/aditya129456/clash-timers/issues/new/choose) and include the crash report from the app if there is one.
- **Larger changes:** open an issue first, so we can agree on the approach before you spend time on it.
- **Tests:** run `./gradlew :app:testDebugUnitTest` before opening a pull request, and add tests for changes to the parser or the timer engine.

A few rules keep the project working:

- **Never commit a real village export.** It contains your player tag. Test data goes in `app/src/test/resources/fixtures`, with the tag replaced by `#TEST000`.
- **Keep R8 off.** Code shrinking caused a start-up crash on Android 10 that the build checks didn't catch.
- **Widget layouts** can only use classes that RemoteViews allows. Anything else, even a plain `View`, stops the whole widget from loading.
- **No network access.** The app has no internet permission, and that won't change.

Your contributions are licensed under the MIT License, the same as the project's code.
