# Lodge Billing System

![Build and test](https://github.com/hopemusenga26-cell/GROUP-1-LAB-3/actions/workflows/tests.yml/badge.svg)

**GROUP-1-LAB-3**

A desktop application (JavaFX) that runs a small lodge's front desk: bookings and walk-ins, a tape chart of
rooms against dates, guest folios with charges, payments and VAT, room housekeeping, receipts,
a dashboard and reports.

## Features

- First-run setup: whoever installs the app creates the admin account (there is no built-in password)
- Staff accounts with roles: **Admin**, **Front desk** and **Housekeeping**. There is no sign-up: only an admin
  creates accounts, on the **Staff** page, and giving someone Admin needs a second confirmation
- New and reset accounts get a one-time password made by the app (e.g. `k7mp-q4xr-9tbn`), shown to the admin once
- Account lockout: 5 wrong passwords lock the account for 5 minutes
- Change your own password; an admin can reset a password and force a change at next sign-in
- **Rooms:** nine rooms seeded on first start (101-104 Standard, 201-203 Deluxe, 301-302 Family); admins can add more.
  Housekeeping marks rooms Available, Dirty or Under maintenance, and gets a **to-clean list**: dirty rooms and
  rooms whose guest leaves today, the ones a guest arrives in soonest at the top, with a Done button
- **Bookings:** book ahead or check in a walk-in; double-booking a room is refused; edit, cancel, check in, check out.
  A walk-in can pay in the same window (in full or a deposit): the booking, check-in and payment are saved
  together, so a refused payment leaves no half-made booking behind
- **Tape chart:** rooms down the side, dates across the top; click a stay to open its folio, click an empty cell to book
- **Guest folio:** post bar, restaurant, laundry, transport and other charges; take payments by cash, card,
  mobile money or bank transfer; split the bill between the guest and a company; void a mistake with a reason;
  print the receipt
- **Taking payment:** one payment window everywhere (folio, Stays page, dashboard, receipts list). The amount
  starts at what is owed, so paying in full is one press of Enter; lower it to take part. With a company on the
  bill there is one amount per payer. "Cash received" works out the change to hand back
- **Check-out:** if money is still owed, the payment window opens with the amount due and paying checks the
  guest out in the same step; after check-out the app offers to print the receipt. Only if that is cancelled
  can an admin force the check-out (the debt stays on the Outstanding report)
- **Dashboard:** today's arrivals and departures with Check in, Take payment and Check out buttons, occupancy,
  outstanding balances, payments today, recent activity
- **Reports (admin):** daily room nights sold, occupancy, ADR and RevPAR, payments received, and who still owes
  money; both export to CSV (safe to open in Excel: values starting with = + - @ are neutralised)
- **Data export (admin):** Reports > *Export all data* writes a dated folder with `bookings.csv`, `folio_lines.csv`,
  `daily_stats.csv` and a `manifest.json` (format version, row counts), ready for Python, Excel or a database.
  No guest names, phone numbers or notes are exported; a salted hash (`guest_key`) still lets you count repeat guests
- **End of day:** a night audit lists no-shows (bookings that never arrived; one click marks them, which frees
  the room and keeps any deposit), guests still to leave or who stayed past check-out, and rooms that are
  dirty or under maintenance for the next arrivals. Then the cash-up: takings per member of staff and
  payment method, the drawer counted against what it should hold, and closing the day so its payments
  can no longer change
- **Rates and VAT:** admins can change room prices and the VAT %. Existing bookings keep the price they were made at
- **Receipts:** every walk-in and booking under its booking number (BK0001), with the charges, VAT and
  payments from its folio, plus the quick receipts (R001, R002, ...) saved by earlier versions. View, print,
  and take the rest of a part-paid balance (e.g. the second half of a 50% deposit); admins can delete an old
  R-receipt. Walk-ins are billed through the Walk-in button, so every guest has a real room and VAT
- Audit log of sign-ins, lockouts, payments, voids and account changes (admin-only, on the Staff page)
- Light (warm stone) and dark (forest green) themes, with an animated aurora behind the sign-in screen
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
- **Total** = room charge - discount + extras + VAT (16% by default; the old quick R-receipts carried no VAT)
- **Balance** = total - amount paid
- A stay is at most 60 nights, and no payment can be more than what that payer still owes

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
**Staff** page: pick their role, and the app shows a one-time
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

### Data export format (version 1)

| File | One row per | Columns |
| ---- | ----------- | ------- |
| `bookings.csv` | booking or walk-in | booking_ref, status, room_number, room_type, check_in, check_out, nights, nightly_rate_k, vat_percent, has_company, company_pays_room, guest_key, created_by, created_at |
| `folio_lines.csv` | charge, payment or refund | entry_id, booking_ref, kind, category, amount_k, payer, posted_at, posted_by, voided |
| `daily_stats.csv` | day, first check-in to today | date, rooms, rooms_sold, occupancy_pct, room_revenue_k, adr_k, revpar_k, payments_k |

Dates are ISO (`2026-10-27`), money is plain kwacha decimals, files are UTF-8 without a byte-order mark.
`manifest.json` is written last, so if it is missing the export did not finish. If a column ever changes,
`format_version` goes up.

### Analysing an export

`analysis/analyze_export.py` checks an export against the format above (manifest, columns, row counts) and
prints occupancy, ADR, RevPAR, payments by method, the no-show rate, average stay and repeat guests:

```
py -m pip install -r analysis\requirements.txt
py analysis\analyze_export.py data
py analysis\analyze_export.py data --json > summary.json
py -m pytest analysis
```

Given a folder of exports it uses the newest finished one. It stops with exit code 2 if the export is
incomplete or in a format it does not know, rather than printing wrong numbers.

### Database upgrades

When a new version changes the tables, the change is a numbered **migration** in
`dao/Migrations.java`. The database remembers which ones it already has (table `schema_migrations`), and
on start-up the app applies any newer ones, in order, each all-or-nothing. So you can `git pull` at any
time and keep your data. A database last opened by a *newer* version of the app is refused rather than
risked: update the app first. When you change the schema, add a migration at the end of the list;
never edit an old one or the `CREATE TABLE` statements in `Database`.

## Keyboard shortcuts

| Shortcut | Action                                |
| -------- | ------------------------------------- |
| Ctrl+1.. | Go to the 1st, 2nd... page in the sidebar |
| Ctrl+R   | View the selected receipt (Receipts page) |
| Ctrl+P   | Print the selected receipt (Receipts page) |
| Ctrl+Y   | Take payment on the selected receipt (Receipts page) |
| Ctrl+D   | Delete the selected R-receipt (admin)  |
| Ctrl+T   | Switch light/dark mode                |
| Ctrl+L   | Log out                               |
| Ctrl+Q   | Close                                 |

In the receipts table, Enter opens the selected receipt and Delete asks to delete it. In a guest folio,
Ctrl+Shift+C adds a charge, Ctrl+Shift+P takes a payment and Esc closes it. In every payment window, Enter
records the payment.
(On macOS, use Cmd instead of Ctrl.)

## Project structure

The project follows the MVC pattern, with the DAO (data access object) pattern for storage. Each table
has an interface in `dao/` that says what can be done with it (for example `BillDao`), and a class that
does it in SQLite (`SqliteBillDao`). The services and screens only use the interfaces; `LodgeBillingApp`
is the one place that creates the SQLite classes.

```
src/main/java/lodgebilling/
├── LodgeBillingApp.java      Entry point: opens the database, then setup -> sign in -> main window
├── Launcher.java             Start this one from IntelliJ
├── model/                    Bill, RoomType, audit entries, report rows, validation, the observable app state
│   ├── security/             Roles, permissions, password hashing and rules, the signed-in session
│   └── service/              AuthService (sign-in, accounts) and LodgeService (front-desk rules)
├── dao/                      DAO interfaces and their SQLite classes: receipts, users, rooms, bookings,
│                             folio lines, settings, reports, audit log; plus the database connection
├── view/                     JavaFX screens: setup, login, main window, receipts, dialogs
│   └── pages/                Dashboard, tape chart, stays, rooms, reports, folio window, booking dialog
└── controller/               Connects the views to the model: BillingController, PageContext (what
                              every page needs), CsvWriter for the report exports
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
 

