# MTP Commander Privacy Policy

Last updated: October 26, 2026

## Overview

MTP Commander does not collect, store or share any personal data. It has no accounts, no analytics, no ads and no servers of its own.

MTP Commander (package `cc.buessow.mtpcommander`) is an Android file manager. It copies files between your phone, devices connected over USB (such as Garmin watches and USB drives) and, if you choose, your own Google Drive. Every file operation runs on your phone at your request.

## Data the app accesses

The app reads and writes files only to carry out the copy, move, delete or browse actions you start. It never uploads anything on its own.

| Source | What the app accesses | Where it goes |
| --- | --- | --- |
| Phone storage | Files and folders you browse, copy or delete | Stays on your phone, or goes to the destination you pick |
| USB devices over MTP (e.g. Garmin watches) | The device's file list, the files you copy, and its USB vendor and product IDs | Stays on your phone, or goes to the destination you pick |
| Garmin device files | A small part of a device's FIT files, read to show the watch model's name | Used on the phone only, never stored or sent |
| USB drives | Files in folders you grant access to through Android's folder picker | Stays on your phone, or goes to the destination you pick |
| Google Drive | Only files and folders that MTP Commander itself created | Transferred directly between your phone and Google |

The app saves a few display settings on the phone, such as sort order and which tips you have dismissed. They contain no personal data. Android may include them in your device backup if backups are on.

## Data collection and sharing

The developer receives no data from the app. Nothing is sold, shared with advertisers or used for tracking.

- **No collection:** the app has no analytics, crash reporting, advertising or tracking libraries, and no developer-run servers.
- **Google Drive:** when you use the Drive pane, the app connects only to Google's servers, using an access token issued by Google Play services. Files you copy travel directly between your phone and your Google account. Google handles this data under the [Google Privacy Policy](https://policies.google.com/privacy).
- **Limited Drive scope:** the app requests only the `drive.file` permission, so it can see only files and folders it created. It cannot read the rest of your Drive.
- **Your Google account:** the app does not read or store your name, email address or contacts. It holds only the access token, which Google Play services manages.
- **Diagnostic logs:** the app writes technical messages, such as USB transfer timings, to the phone's local system log. These stay on the device and are not sent anywhere.

## Permissions

Each permission is used only for the file operations described above.

| Permission | Why the app needs it |
| --- | --- |
| All files access (`MANAGE_EXTERNAL_STORAGE`) | To browse and copy files anywhere in your phone's shared storage, as a file manager does |
| USB host | To talk to MTP devices and USB drives you plug in |
| Internet (`INTERNET`) | Only to reach Google Drive when you open the Drive pane |
| Google Drive (`drive.file`) | To list, upload, download and trash files the app created in your Drive |
| Folder access (Android folder picker) | To read and write folders on USB drives you choose |

You can revoke any of these in Android settings. Drive access can be removed at [myaccount.google.com/connections](https://myaccount.google.com/connections).

## Children

The app is not directed at children under 13 and collects no data from anyone, including children.

## Security

All connections to Google Drive use HTTPS. Files on your phone and USB devices are protected by Android's own storage permissions; the app adds no copies outside the destinations you choose.

## Changes to this policy

If the app's data handling changes, this policy will be updated and the date at the top revised before the new version is released.

## Contact

Questions about this policy: [rb.codecraft@gmail.com](mailto:rb.codecraft@gmail.com).
