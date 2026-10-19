# Lodge Billing System

**GROUP-1-LAB-3**

A desktop application (JavaFX) that runs a small lodge's front desk: bookings and walk-ins, a tape chart of
rooms against dates, guest folios with charges, payments and VAT, room housekeeping, quick walk-in receipts,
a dashboard and reports.

## Features

- First-run setup: whoever installs the app creates the admin account (there is no built-in password)
- Staff accounts with roles: **Admin**, **Front desk** and **Housekeeping**. There is no sign-up: only an admin
  creates accounts, on the **Staff** page, and giving someone Admin needs a second confirmation
- New and reset accounts get a one-time password made by the app (e.g. `k7mp-q4xr-9tbn`), shown to the admin once
- Account lockout: 5 wrong passwords lock the account for 5 minutes
- Change your own password; an admin can reset a password and force a change at next sign-in
- **Rooms:** nine rooms seeded on first start (101-104 Standard, 201-203 Deluxe, 301-302 Family); admins can add more.
  Housekeeping marks rooms Available, Dirty or Under maintenance
- **Bookings:** book ahead or check in a walk-in; double-booking a room is refused; edit, cancel, check in, check out
- **Tape chart:** rooms down the side, dates across the top; click a stay to open its folio, click an empty cell to book
- **Guest folio:** post bar, restaurant, laundry, transport and other charges; take payments by cash, card,
  mobile money or bank transfer; split the bill between the guest and a company; void a mistake with a reason.
  Check-out is blocked while money is owed (an admin can force it)
- **Dashboard:** today's arrivals and departures, occupancy, outstanding balances, payments today, recent activity
- **Reports (admin):** daily room nights sold, occupancy, ADR and RevPAR, payments received, and who still owes
  money; both export to CSV (safe to open in Excel: values starting with = + - @ are neutralised)
- **Rates and VAT:** admins can change room prices and the VAT %. Existing bookings keep the price they were made at
- Walk-in receipts: guest form with validation (name, room type, nights, extras, amount paid, notes)
- Automatic calculation of room charge, discount, total and balance
- Receipts saved in a SQLite database, so they survive closing the app (R001, R002, ...)
- View, print and delete receipts (deleting is admin-only)
- Audit log of sign-ins, lockouts, saved and deleted receipts and account changes (admin-only)
- Light and dark mode over an animated aurora background
- Keyboard shortcuts for common actions

## Billing rules

Starting prices (an admin can change them under **Rooms > Rates & VAT**):

| Room type | Price per night |
| --------- | --------------- |
| Standard  | K 350.00        |
| Deluxe    | K 550.00        |
| Family    | K 750.00        |

- **Room charge** = price per night x number of nights
- **Discount:** 5% off the room charge for stays of **5 nights or more** (extras are not discounted)
- **Total** = room charge - discount + extras (receipts carry no tax; stays add VAT, 16% by default)
- **Balance** = total - amount paid
- A receipt covers at most 30 nights (a booking up to 60), and the amount paid on a receipt cannot be more than its total

## Requirements

- JDK 21 or newer (the code is compiled for Java 21, so it also builds on JDK 25)
- Internet connection the first time you build (Gradle downloads JavaFX, SQLite and JUnit)

Gradle does not need to be installed. The project includes the Gradle wrapper.

## How to run

```
git clone https://github.com/hopemusenga26-cell/GROUP-1-LAB-3.git
cd GROUP-1-LAB-3
./gradlew run
```

On Windows (Command Prompt or PowerShell), use `gradlew.bat run` instead of `./gradlew run`.

In IntelliJ, run `Launcher` (not `LodgeBillingApp`): see the comment in `Launcher.java` for why.

Run the tests with `gradlew.bat test` (or `./gradlew test`).

## First start

The first time the app opens it asks you to create the **admin account**. Passwords need at least
8 characters with a letter and a number. The admin then adds everyone else on the
**Staff** page (or **Admin > Staff accounts**, Ctrl+U): pick their role, and the app shows a one-time
password to hand over in private. They choose their own password the first time they sign in.
The Staff page also shows who still has to do that, and when each person last signed in.

| Role         | Can do                                                                        |
| ------------ | ----------------------------------------------------------------------------- |
| Housekeeping | See rooms and mark them clean, dirty or under maintenance                     |
| Front desk   | Bookings, check-in/out, folio charges and payments, receipts, room status     |
| Admin        | Everything, plus voids, forced check-out, rooms, rates, reports, users, audit log |

## Where the data is kept

Everything is stored in a SQLite database, `data/lodgebilling.db`, inside the project folder. It is
created on the first start. The `data/` folder is listed in `.gitignore`, so guest details are never
committed to Git. Start the app from the project folder (`gradlew run` and IntelliJ both do), or it
will create a new, empty database wherever it was started.

Receipts saved by the earlier version of the app (`receipts.tsv` in a `.lodgebilling` folder in your
home folder, for example `C:\Users\<you>\.lodgebilling\receipts.tsv`) are copied into
the database automatically on the first start, keeping their receipt numbers. The old file is then
renamed to `receipts.tsv.imported`.

Passwords are never stored: each one is kept as a salted PBKDF2 hash.

## Keyboard shortcuts

| Shortcut | Action                                |
| -------- | ------------------------------------- |
| Ctrl+1.. | Go to the 1st, 2nd... page in the sidebar |
| Ctrl+N   | New guest (receipt)                   |
| Ctrl+R   | View receipt                          |
| Ctrl+P   | Print receipt                         |
| Ctrl+D   | Delete selected receipt (admin)       |
| Ctrl+T   | Switch light/dark mode                |
| Ctrl+U   | Staff accounts (admin)                |
| Ctrl+L   | Log out                               |
| Ctrl+Q   | Close                                 |

In the receipts table, Enter opens the selected receipt and Delete asks to delete it.
(On macOS, use Cmd instead of Ctrl.)

## Project structure

The project follows the MVC pattern, with repositories for storage.

```
src/main/java/lodgebilling/
├── LodgeBillingApp.java      Entry point: opens the database, then setup -> sign in -> main window
├── Launcher.java             Start this one from IntelliJ
├── model/                    Bill, RoomType, validation, the observable app state
├── data/                     SQLite: receipts, users, rooms, bookings, folio lines, settings, audit log
├── security/                 Roles, permissions, password hashing and rules, the signed-in session
├── service/                  AuthService (sign-in, accounts) and LodgeService (front-desk rules)
├── view/                     JavaFX screens: setup, login, main window, receipts, dialogs
│   └── pages/                Dashboard, tape chart, stays, rooms, reports, folio window, booking dialog
├── util/                     CsvWriter for the report exports
└── controller/               Connects the views to the model
src/main/resources/           Stylesheet (lodge.css)
src/test/java/lodgebilling/   JUnit tests
```

## Team

1.  Musenga Hope Sichula
2.  Yande Sichula
3.  Charles Junior Makwembo
4.  Muonga Kafuti
5.  Kasamba Faith Masinja
6.  Mulenga Mwamba
7.  Nyangu Chimwemwe Mubanga
8.  Namuungo Agness Mulungu
9.  Nikiwe Taonga Mwale
10.  Ugolisa Isaac Urobo
11.  Bumi Lukomona
12.  Siame Ndinawe
13.  Samuel Siame
 

