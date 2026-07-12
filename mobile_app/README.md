# HTTP Reading Mobile

Flutter mobile MVP for the Spring Boot backend in this repository.

## Run

This environment does not include the Flutter SDK, so platform folders are not generated here.
On a machine with Flutter installed:

```bash
cd mobile_app
flutter create . --platforms=android,ios
flutter pub get
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080
```

Use `http://10.0.2.2:8080` for the Android emulator and your LAN IP for a real phone.

## MVP Scope

- Login and register with JWT storage.
- Browse public books through `/api/mobile/books`.
- View bookshelf and add books after login.
- Read chapters, save progress, and restore progress.
- Select text in the reader and ask `/api/ai/chat`.
- Handle token expiration, network failures, business errors, and AI timeout states.
