package au.edu.rmit.sct;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Person class implements three methods:
 *   1) addPerson()
 *   2) updatePersonalDetails(...)
 *   3) addDemeritPoints(...)
 *
 * Data is persisted in two plain-text files:
 *   - persons.txt       (one line per person: personID|firstName|lastName|address|birthdate)
 *   - demeritPoints.txt (one line per offense: personID|offenseDate|points)
 */
public class Person {

    private String personID;     // 10-char ID, first two digits 2–9, ≥2 special chars in pos 3–8, last two uppercase letters
    private String firstName;
    private String lastName;
    private String address;      // Format: StreetNo|Street|City|State|Country (State must be "Victoria")
    private String birthdate;    // Format: DD-MM-YYYY
    private boolean isSuspended = false;

    private static final Path PERSONS_FILE  = Paths.get("persons.txt");
    private static final Path DEMERITS_FILE = Paths.get("demeritPoints.txt");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    /**
     * Constructor: simply stores the fields in memory.
     * (Writing to disk only happens when addPerson() or updatePersonalDetails() is called.)
     */
    public Person(String personID,
                  String firstName,
                  String lastName,
                  String address,
                  String birthdate) {
        this.personID  = personID;
        this.firstName = firstName;
        this.lastName  = lastName;
        this.address   = address;
        this.birthdate = birthdate;
    }

    /**
     * addPerson():
     *   - Condition 1: personID must be exactly 10 characters:
     *       * first two characters are digits between '2' and '9'
     *       * at least two special characters (non-alphanumeric) between positions 3 and 8 (indices 2..7)
     *       * last two characters are uppercase letters A–Z
     *   - Condition 2: address must be in the form "StreetNo|Street|City|State|Country"
     *       * exactly 5 parts when splitting on '|'
     *       * part[3] (the “State” field) must equal exactly "Victoria"
     *       * part[0] (StreetNo) must parse as an integer
     *   - Condition 3: birthdate must parse as DD-MM-YYYY
     *
     * If all three conditions pass, append a single line to persons.txt:
     *    personID|firstName|lastName|address|birthdate
     * Return true if the write succeeds; otherwise return false.
     */
    public boolean addPerson() {
        // 1) Validate ID
        if (!validatePersonID(personID)) {
            return false;
        }
        // 2) Validate address
        if (!validateAddress(address)) {
            return false;
        }
        // 3) Validate birthdate format
        if (!validateDate(birthdate)) {
            return false;
        }

        // Build the pipe-delimited record
        String record = String.join("|",
            personID,
            firstName,
            lastName,
            address,
            birthdate
        );

        try {
            // If persons.txt does not exist, CREATE it; otherwise, APPEND to it
            Files.write(
                PERSONS_FILE,
                Collections.singletonList(record),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
            return true;
        } catch (IOException e) {
            // If any I/O error occurs, we signal failure
            return false;
        }
    }

    /**
     * updatePersonalDetails(
     *     originalID,
     *     newID,
     *     newFirstName,
     *     newLastName,
     *     newAddress,
     *     newBirthdate
     * )
     *
     * This method modifies an existing person’s record in persons.txt.
     * It enforces these additional rules on top of the addPerson() checks:
     *
     *   (A) Re‐validate newID, newAddress, newBirthdate using the same rules as addPerson().
     *
     *   (B) If the person’s **current age** is under 18, then newAddress must be identical to the stored address.
     *
     *   (C) If newBirthdate differs from the stored birthdate, then no other personal detail
     *       (newID, newFirstName, newLastName, newAddress) may change. Only the birthdate alone
     *       can be updated—no other field may change simultaneously.
     *
     *   (D) If the first digit of originalID is an **even** number, then ID cannot be changed.
     *
     * If all conditions pass, we:
     *   1. Read every line of persons.txt into memory.
     *   2. Locate the single line whose first field equals originalID.
     *   3. Replace that line with:
     *        newID|newFirstName|newLastName|newAddress|newBirthdate
     *   4. Overwrite persons.txt with the updated list of lines.
     *   5. Update this Person object’s fields (personID, firstName, lastName, address, birthdate) in memory.
     *   6. Return true.
     *
     * If any condition fails, or any I/O error occurs, we do NOT touch the file and return false.
     */
    public boolean updatePersonalDetails(
        String originalID,
        String newID,
        String newFirstName,
        String newLastName,
        String newAddress,
        String newBirthdate
    ) {
        // ----------------------------------
        // Step 1: Re‐validate new fields
        // ----------------------------------
        if (!validatePersonID(newID)) {
            return false;
        }
        if (!validateAddress(newAddress)) {
            return false;
        }
        if (!validateDate(newBirthdate)) {
            return false;
        }

        // ----------------------------------
        // Step 2: Determine the person’s current age (based on stored birthdate)
        // ----------------------------------
        LocalDate dob = LocalDate.parse(this.birthdate, DATE_FMT);
        int ageNow = Period.between(dob, LocalDate.now()).getYears();

        // Condition (B): If under 18, cannot change address
        if (ageNow < 18 && !newAddress.equals(this.address)) {
            return false;
        }

        // Condition (C): If the birthdate is changing, no other field may change
        boolean birthChanged = !newBirthdate.equals(this.birthdate);
        boolean nameOrIDOrAddressChanged =
               !newID.equals(originalID)
            || !newFirstName.equals(this.firstName)
            || !newLastName.equals(this.lastName)
            || !newAddress.equals(this.address);

        if (birthChanged && nameOrIDOrAddressChanged) {
            return false;
        }

        // ----------------------------------
        // Condition (D): If originalID starts with an even digit, ID cannot change
        // ----------------------------------
        char firstChar = originalID.charAt(0);
        if (Character.isDigit(firstChar)) {
            int digitValue = firstChar - '0';
            if ((digitValue % 2) == 0 && !newID.equals(originalID)) {
                return false;
            }
        }

        // ----------------------------------
        // Step 3: Rewrite persons.txt with updated line
        // ----------------------------------
        try {
            // Read all existing lines
            List<String> allLines = Files.readAllLines(PERSONS_FILE);

            List<String> updatedLines = new ArrayList<>(allLines.size());
            boolean replacedOne = false;

            for (String line : allLines) {
                // Split into at most 5 parts:
                //   [0]=personID, [1]=firstName, [2]=lastName, [3]=address, [4]=birthdate
                String[] parts = line.split("\\|", 5);
                if (parts.length < 5) {
                    // Malformed line—just keep it unchanged
                    updatedLines.add(line);
                    continue;
                }

                if (parts[0].equals(originalID) && !replacedOne) {
                    // This is the line we want to replace exactly once
                    String newRecord = String.join("|",
                        newID,
                        newFirstName,
                        newLastName,
                        newAddress,
                        newBirthdate
                    );
                    updatedLines.add(newRecord);
                    replacedOne = true;
                } else {
                    // Keep the existing line unchanged
                    updatedLines.add(line);
                }
            }

            // Overwrite persons.txt with the updated content
            Files.write(
                PERSONS_FILE,
                updatedLines,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );

            // Update this object’s fields in memory:
            this.personID  = newID;
            this.firstName = newFirstName;
            this.lastName  = newLastName;
            this.address   = newAddress;
            this.birthdate = newBirthdate;

            return true;

        } catch (IOException e) {
            // If any I/O error happens while reading/writing, signal failure
            return false;
        }
    }

    /**
     * addDemeritPoints(offenseDate, pts):
     *   - Condition 1: offenseDate must parse in format DD-MM-YYYY.
     *   - Condition 2: pts must be an integer between 1 and 6 (inclusive).
     *
     * If either condition fails, return "Failed" immediately (and do not touch the file).
     * Otherwise:
     *   1) Append one line to demeritPoints.txt:
     *        personID|offenseDate|pts
     *   2) Read every line of demeritPoints.txt, and sum up all points for this personID
     *      whose offense date is within the last two years (inclusive).
     *   3) Calculate the person’s age **on** the offenseDate (using this.birthdate).
     *      If age < 21, threshold = 6; otherwise threshold = 12.
     *   4) If total points in that two‐year window exceed the threshold,
     *      set this.isSuspended = true.
     *   5) Always return "Success" if we wrote now.
     */
    public String addDemeritPoints(String offenseDate, int pts) {
        // 1) Validate offenseDate format
        if (!validateDate(offenseDate)) {
            return "Failed";
        }
        // 2) Validate pts in [1..6]
        if (pts < 1 || pts > 6) {
            return "Failed";
        }

        // Parse the offenseDate into a LocalDate
        LocalDate od;
        try {
            od = LocalDate.parse(offenseDate, DATE_FMT);
        } catch (DateTimeParseException e) {
            // Should not reach here because validateDate already checked format,
            // but just in case, fail:
            return "Failed";
        }

        // Build the record to append
        String record = String.join("|",
            personID,
            offenseDate,
            String.valueOf(pts)
        );

        try {
            // Append to demeritPoints.txt (create if not exists)
            Files.write(
                DEMERITS_FILE,
                Collections.singletonList(record),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            return "Failed";
        }

        // 3) Sum up all points in the last two years for this person
        LocalDate cutoff = od.minusYears(2);
        int runningTotal = 0;

        try {
            List<String> lines = Files.readAllLines(DEMERITS_FILE);
            for (String line : lines) {
                String[] parts = line.split("\\|", 3);
                if (parts.length < 3) {
                    continue; // skip malformed lines
                }
                if (!parts[0].equals(personID)) {
                    continue; // skip other persons
                }
                // Parse the offense date
                LocalDate recordDate = LocalDate.parse(parts[1], DATE_FMT);
                int recordPts = Integer.parseInt(parts[2]);
                // If recordDate is on or after the cutoff, include it
                if (!recordDate.isBefore(cutoff)) {
                    runningTotal += recordPts;
                }
            }
        } catch (IOException e) {
            // If reading failed for some reason, we cannot recalculate properly;
            // but we already appended our line, so still return "Success".
            // (We do not set suspended in this catch.)
        }

        // 4) Calculate age on the offense date
        LocalDate dob;
        try {
            dob = LocalDate.parse(this.birthdate, DATE_FMT);
        } catch (DateTimeParseException ex) {
            // In theory, birthdate was already validated when the person was created/updated.
            // If it somehow is invalid, just skip suspension logic and return "Success."
            return "Success";
        }
        int ageOnOffense = Period.between(dob, od).getYears();
        int threshold = (ageOnOffense < 21) ? 6 : 12;

        if (runningTotal > threshold) {
            this.isSuspended = true;
        }

        return "Success";
    }

    // ───────────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPER METHODS (used internally by addPerson, updatePersonalDetails, addDemeritPoints)
    // ───────────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if and only if:
     *   - id is non-null
     *   - length is exactly 10
     *   - first two characters are digits '2'–'9'
     *   - there are at least 2 special chars (non-letter, non-digit) among positions 2..7
     *   - last two characters are uppercase letters 'A'–'Z'
     */
    private boolean validatePersonID(String id) {
        if (id == null || id.length() != 10) {
            return false;
        }
        // Check first two chars:
        for (int i = 0; i < 2; i++) {
            char c = id.charAt(i);
            if (!Character.isDigit(c) || c < '2' || c > '9') {
                return false;
            }
        }
        // Check last two chars are uppercase A–Z
        for (int i = 8; i < 10; i++) {
            char c = id.charAt(i);
            if (c < 'A' || c > 'Z') {
                return false;
            }
        }
        // Count how many specials (non-letter, non-digit) appear in positions 2..7
        int specialCount = 0;
        for (int i = 2; i < 8; i++) {
            char c = id.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                specialCount++;
            }
        }
        return (specialCount >= 2);
    }

    /**
     * Returns true if and only if:
     *   - addr is non-null
     *   - Splitting on "|" yields exactly 5 parts
     *   - parts[3].equals("Victoria")
     *   - parts[0] (StreetNo) parses as an integer
     */
    private boolean validateAddress(String addr) {
        if (addr == null) {
            return false;
        }
        String[] parts = addr.split("\\|", -1);
        if (parts.length != 5) {
            return false;
        }
        // Check State == "Victoria"
        if (!"Victoria".equals(parts[3])) {
            return false;
        }
        // Check StreetNo parses as integer
        try {
            Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return false;
        }
        return true;
    }

    /**
     * Returns true if and only if:
     *   - d is non-null
     *   - d parses successfully as a LocalDate with format "dd-MM-yyyy"
     */
    private boolean validateDate(String d) {
        if (d == null) {
            return false;
        }
        try {
            LocalDate.parse(d, DATE_FMT);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** Getter for the suspension status (used by unit tests). */
    public boolean isSuspended() {
        return isSuspended;
    }
}
