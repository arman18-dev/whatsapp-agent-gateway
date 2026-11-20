-- The patient's name as given, stored with the booking (the lab gets it too).
ALTER TABLE bookings ADD COLUMN patient_name TEXT;
