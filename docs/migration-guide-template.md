# Migration Guide: Upgrading from vX.X to vY.Y

This guide provides detailed instructions for upgrading your application from version X.X to Y.Y.

## ⚠️ Breaking Changes

This is the most critical section. Please review it carefully.

### 1. API Endpoint Renamed

- **Before:** `POST /api/v1/getData`
- **After:** `POST /api/v2/data`
- **Action Required:** You must update all API calls to the new endpoint.

### 2. Configuration File Change

The `timeout` setting in `config.json` is now measured in milliseconds instead of seconds.
- **Before:** `{ "timeout": 10 }` (meaning 10 seconds)
- **After:** `{ "timeout": 10000 }` (meaning 10 seconds)
- **Action Required:** Multiply your existing timeout values by 1000.

## ✨ New Features

- A new real-time caching mechanism has been introduced.

## 🗑️ Deprecations

- The function `calculateLegacyData()` is now deprecated and will be removed in vZ.Z. Please use `calculateData()` instead.

## 📝 Step-by-Step Upgrade Checklist

1.  [ ] Backup your database and configuration files.
2.  [ ] Update the application dependency to version Y.Y.
3.  [ ] Run the database migration script: `npm run migrate`.
4.  [ ] Update your configuration files as described above.
5.  [ ] Test the application thoroughly in a staging environment.