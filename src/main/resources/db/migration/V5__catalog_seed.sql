-- V5 — Catalog seed
--
-- Reference data for the Indian market. Idempotent via ON CONFLICT so a partial
-- run can be repeated safely.
--
-- Slugs here become public URLs. Renaming one after launch means a redirect,
-- not an edit, so they are chosen to read naturally to a parent searching
-- Google: "class-10-mathematics", not "cls10-math".

-- Boards ----------------------------------------------------------------------
INSERT INTO boards (name, slug, display_order) VALUES
    ('CBSE',                    'cbse',            1),
    ('ICSE',                    'icse',            2),
    ('State Board — Telangana', 'state-telangana', 3),
    ('State Board — Andhra Pradesh', 'state-ap',   4),
    ('State Board — Karnataka', 'state-karnataka', 5),
    ('State Board — Maharashtra', 'state-maharashtra', 6),
    ('State Board — Tamil Nadu', 'state-tamil-nadu', 7),
    ('IB',                      'ib',              8),
    ('IGCSE',                   'igcse',           9),
    ('NIOS',                    'nios',           10)
ON CONFLICT (slug) DO NOTHING;

-- Grade levels ----------------------------------------------------------------
INSERT INTO grade_levels (name, slug, display_order) VALUES
    ('Nursery / Pre-KG', 'nursery',        1),
    ('LKG',              'lkg',            2),
    ('UKG',              'ukg',            3),
    ('Class 1',          'class-1',        4),
    ('Class 2',          'class-2',        5),
    ('Class 3',          'class-3',        6),
    ('Class 4',          'class-4',        7),
    ('Class 5',          'class-5',        8),
    ('Class 6',          'class-6',        9),
    ('Class 7',          'class-7',       10),
    ('Class 8',          'class-8',       11),
    ('Class 9',          'class-9',       12),
    ('Class 10',         'class-10',      13),
    ('Class 11',         'class-11',      14),
    ('Class 12',         'class-12',      15),
    ('Undergraduate',    'undergraduate', 16),
    ('Postgraduate',     'postgraduate',  17),
    ('Competitive Exam', 'competitive',   18),
    ('Adult Learner',    'adult',         19)
ON CONFLICT (slug) DO NOTHING;

-- Subjects: top level ---------------------------------------------------------
INSERT INTO subjects (parent_id, name, slug, is_leaf, display_order) VALUES
    (NULL, 'School Tuition',      'school-tuition',      FALSE, 1),
    (NULL, 'Exam Preparation',    'exam-preparation',    FALSE, 2),
    (NULL, 'Languages',           'languages',           FALSE, 3),
    (NULL, 'Computers & IT',      'computers-it',        FALSE, 4),
    (NULL, 'Music & Dance',       'music-dance',         FALSE, 5),
    (NULL, 'Study Abroad Tests',  'study-abroad-tests',  FALSE, 6),
    (NULL, 'Hobbies & Sports',    'hobbies-sports',      FALSE, 7)
ON CONFLICT (slug) DO NOTHING;

-- School Tuition -> subjects ---------------------------------------------------
-- Kept one level deep rather than splitting per class: a parent searches
-- "Class 10 Maths tutor", and the class is already a grade-level filter. Adding
-- a class tier would multiply the subject tree by twelve for no extra meaning.
INSERT INTO subjects (parent_id, name, slug, is_leaf, display_order)
SELECT s.id, v.name, v.slug, TRUE, v.ord
FROM subjects s, (VALUES
    ('Mathematics',       'mathematics',       1),
    ('Physics',           'physics',           2),
    ('Chemistry',         'chemistry',         3),
    ('Biology',           'biology',           4),
    ('Science',           'science',           5),
    ('English',           'english',           6),
    ('Hindi',             'hindi',             7),
    ('Social Science',    'social-science',    8),
    ('History',           'history',           9),
    ('Geography',         'geography',        10),
    ('Computer Science',  'computer-science', 11),
    ('Accountancy',       'accountancy',      12),
    ('Economics',         'economics',        13),
    ('Business Studies',  'business-studies', 14),
    ('Political Science', 'political-science',15)
) AS v(name, slug, ord)
WHERE s.slug = 'school-tuition'
ON CONFLICT (slug) DO NOTHING;

-- Exam Preparation -------------------------------------------------------------
INSERT INTO subjects (parent_id, name, slug, is_leaf, display_order)
SELECT s.id, v.name, v.slug, TRUE, v.ord
FROM subjects s, (VALUES
    ('JEE Main',            'jee-main',        1),
    ('JEE Advanced',        'jee-advanced',    2),
    ('NEET',                'neet',            3),
    ('CA Foundation',       'ca-foundation',   4),
    ('CAT / MBA Entrance',  'cat-mba',         5),
    ('UPSC Civil Services', 'upsc',            6),
    ('GATE',                'gate',            7),
    ('CLAT / Law Entrance', 'clat',            8),
    ('Bank PO & Clerk',     'bank-exams',      9),
    ('SSC Exams',           'ssc-exams',      10),
    ('NDA / Defence',       'nda-defence',    11)
) AS v(name, slug, ord)
WHERE s.slug = 'exam-preparation'
ON CONFLICT (slug) DO NOTHING;

-- Languages ---------------------------------------------------------------------
INSERT INTO subjects (parent_id, name, slug, is_leaf, display_order)
SELECT s.id, v.name, v.slug, TRUE, v.ord
FROM subjects s, (VALUES
    ('Spoken English', 'spoken-english', 1),
    ('Hindi Language', 'hindi-language', 2),
    ('Telugu',         'telugu',         3),
    ('Tamil',          'tamil',          4),
    ('Kannada',        'kannada',        5),
    ('Marathi',        'marathi',        6),
    ('Bengali',        'bengali',        7),
    ('Sanskrit',       'sanskrit',       8),
    ('French',         'french',         9),
    ('German',         'german',        10),
    ('Spanish',        'spanish',       11),
    ('Japanese',       'japanese',      12)
) AS v(name, slug, ord)
WHERE s.slug = 'languages'
ON CONFLICT (slug) DO NOTHING;

-- Computers & IT ----------------------------------------------------------------
INSERT INTO subjects (parent_id, name, slug, is_leaf, display_order)
SELECT s.id, v.name, v.slug, TRUE, v.ord
FROM subjects s, (VALUES
    ('Python Programming', 'python',           1),
    ('Java Programming',   'java',             2),
    ('C / C++',            'c-cpp',            3),
    ('Web Development',    'web-development',  4),
    ('Data Science',       'data-science',     5),
    ('Machine Learning',   'machine-learning', 6),
    ('MS Office & Excel',  'ms-office',        7),
    ('Tally',              'tally',            8),
    ('Graphic Design',     'graphic-design',   9)
) AS v(name, slug, ord)
WHERE s.slug = 'computers-it'
ON CONFLICT (slug) DO NOTHING;

-- Music & Dance -----------------------------------------------------------------
INSERT INTO subjects (parent_id, name, slug, is_leaf, display_order)
SELECT s.id, v.name, v.slug, TRUE, v.ord
FROM subjects s, (VALUES
    ('Guitar',              'guitar',            1),
    ('Keyboard / Piano',    'keyboard-piano',    2),
    ('Carnatic Vocal',      'carnatic-vocal',    3),
    ('Hindustani Vocal',    'hindustani-vocal',  4),
    ('Violin',              'violin',            5),
    ('Tabla',               'tabla',             6),
    ('Drums',               'drums',             7),
    ('Bharatanatyam',       'bharatanatyam',     8),
    ('Kathak',              'kathak',            9),
    ('Western Dance',       'western-dance',    10)
) AS v(name, slug, ord)
WHERE s.slug = 'music-dance'
ON CONFLICT (slug) DO NOTHING;

-- Study Abroad Tests -------------------------------------------------------------
INSERT INTO subjects (parent_id, name, slug, is_leaf, display_order)
SELECT s.id, v.name, v.slug, TRUE, v.ord
FROM subjects s, (VALUES
    ('IELTS', 'ielts', 1),
    ('TOEFL', 'toefl', 2),
    ('GRE',   'gre',   3),
    ('GMAT',  'gmat',  4),
    ('SAT',   'sat',   5),
    ('PTE',   'pte',   6)
) AS v(name, slug, ord)
WHERE s.slug = 'study-abroad-tests'
ON CONFLICT (slug) DO NOTHING;

-- Hobbies & Sports ---------------------------------------------------------------
INSERT INTO subjects (parent_id, name, slug, is_leaf, display_order)
SELECT s.id, v.name, v.slug, TRUE, v.ord
FROM subjects s, (VALUES
    ('Drawing & Painting', 'drawing-painting', 1),
    ('Chess',              'chess',            2),
    ('Yoga',               'yoga',             3),
    ('Cricket Coaching',   'cricket',          4),
    ('Badminton',          'badminton',        5),
    ('Swimming',           'swimming',         6),
    ('Public Speaking',    'public-speaking',  7)
) AS v(name, slug, ord)
WHERE s.slug = 'hobbies-sports'
ON CONFLICT (slug) DO NOTHING;

-- Cities --------------------------------------------------------------------------
INSERT INTO locations (state, city, locality, slug, latitude, longitude, is_city, display_order) VALUES
    ('Telangana',     'Hyderabad', NULL, 'hyderabad', 17.385000, 78.486700, TRUE, 1),
    ('Karnataka',     'Bengaluru', NULL, 'bengaluru', 12.971600, 77.594600, TRUE, 2),
    ('Maharashtra',   'Mumbai',    NULL, 'mumbai',    19.076000, 72.877700, TRUE, 3),
    ('Delhi',         'New Delhi', NULL, 'delhi',     28.613900, 77.209000, TRUE, 4),
    ('Maharashtra',   'Pune',      NULL, 'pune',      18.520400, 73.856700, TRUE, 5),
    ('Tamil Nadu',    'Chennai',   NULL, 'chennai',   13.082700, 80.270700, TRUE, 6),
    ('West Bengal',   'Kolkata',   NULL, 'kolkata',   22.572600, 88.363900, TRUE, 7),
    ('Gujarat',       'Ahmedabad', NULL, 'ahmedabad', 23.022500, 72.571400, TRUE, 8),
    ('Andhra Pradesh','Visakhapatnam', NULL, 'visakhapatnam', 17.686800, 83.218500, TRUE, 9),
    ('Rajasthan',     'Jaipur',    NULL, 'jaipur',    26.912400, 75.787300, TRUE, 10)
ON CONFLICT (slug) DO NOTHING;

-- Hyderabad localities -------------------------------------------------------------
-- Seeded deepest because it is the assumed launch city. Locality depth drives the
-- SEO page count, so this is the list to revisit when the launch city is confirmed.
INSERT INTO locations (state, city, locality, slug, latitude, longitude, is_city, display_order) VALUES
    ('Telangana', 'Hyderabad', 'Gachibowli',    'hyderabad-gachibowli',    17.440800, 78.348300, FALSE, 1),
    ('Telangana', 'Hyderabad', 'Madhapur',      'hyderabad-madhapur',      17.448500, 78.391300, FALSE, 2),
    ('Telangana', 'Hyderabad', 'Kondapur',      'hyderabad-kondapur',      17.464600, 78.361900, FALSE, 3),
    ('Telangana', 'Hyderabad', 'HITEC City',    'hyderabad-hitec-city',    17.445300, 78.379400, FALSE, 4),
    ('Telangana', 'Hyderabad', 'Kukatpally',    'hyderabad-kukatpally',    17.484700, 78.413800, FALSE, 5),
    ('Telangana', 'Hyderabad', 'Miyapur',       'hyderabad-miyapur',       17.496700, 78.358100, FALSE, 6),
    ('Telangana', 'Hyderabad', 'Ameerpet',      'hyderabad-ameerpet',      17.437400, 78.448700, FALSE, 7),
    ('Telangana', 'Hyderabad', 'Banjara Hills', 'hyderabad-banjara-hills', 17.412600, 78.448300, FALSE, 8),
    ('Telangana', 'Hyderabad', 'Jubilee Hills', 'hyderabad-jubilee-hills', 17.430700, 78.407100, FALSE, 9),
    ('Telangana', 'Hyderabad', 'Begumpet',      'hyderabad-begumpet',      17.444800, 78.464700, FALSE, 10),
    ('Telangana', 'Hyderabad', 'Secunderabad',  'hyderabad-secunderabad',  17.439900, 78.498300, FALSE, 11),
    ('Telangana', 'Hyderabad', 'LB Nagar',      'hyderabad-lb-nagar',      17.351200, 78.552000, FALSE, 12),
    ('Telangana', 'Hyderabad', 'Dilsukhnagar',  'hyderabad-dilsukhnagar',  17.368600, 78.526200, FALSE, 13),
    ('Telangana', 'Hyderabad', 'Uppal',         'hyderabad-uppal',         17.405700, 78.559300, FALSE, 14),
    ('Telangana', 'Hyderabad', 'Manikonda',     'hyderabad-manikonda',     17.402400, 78.383600, FALSE, 15),
    ('Telangana', 'Hyderabad', 'Nizampet',      'hyderabad-nizampet',      17.509600, 78.389000, FALSE, 16),
    ('Telangana', 'Hyderabad', 'Mehdipatnam',   'hyderabad-mehdipatnam',   17.395600, 78.437500, FALSE, 17),
    ('Telangana', 'Hyderabad', 'Tarnaka',       'hyderabad-tarnaka',       17.426900, 78.531300, FALSE, 18),
    ('Telangana', 'Hyderabad', 'Attapur',       'hyderabad-attapur',       17.365300, 78.427100, FALSE, 19),
    ('Telangana', 'Hyderabad', 'Chandanagar',   'hyderabad-chandanagar',   17.499100, 78.323400, FALSE, 20)
ON CONFLICT (slug) DO NOTHING;

-- Bengaluru localities ---------------------------------------------------------------
INSERT INTO locations (state, city, locality, slug, latitude, longitude, is_city, display_order) VALUES
    ('Karnataka', 'Bengaluru', 'Koramangala',     'bengaluru-koramangala',     12.935200, 77.624500, FALSE, 1),
    ('Karnataka', 'Bengaluru', 'Indiranagar',     'bengaluru-indiranagar',     12.971900, 77.640800, FALSE, 2),
    ('Karnataka', 'Bengaluru', 'Whitefield',      'bengaluru-whitefield',      12.969800, 77.749900, FALSE, 3),
    ('Karnataka', 'Bengaluru', 'HSR Layout',      'bengaluru-hsr-layout',      12.911600, 77.638900, FALSE, 4),
    ('Karnataka', 'Bengaluru', 'BTM Layout',      'bengaluru-btm-layout',      12.916600, 77.610100, FALSE, 5),
    ('Karnataka', 'Bengaluru', 'Jayanagar',       'bengaluru-jayanagar',       12.925300, 77.583300, FALSE, 6),
    ('Karnataka', 'Bengaluru', 'Marathahalli',    'bengaluru-marathahalli',    12.956100, 77.701100, FALSE, 7),
    ('Karnataka', 'Bengaluru', 'Electronic City', 'bengaluru-electronic-city', 12.839400, 77.677400, FALSE, 8),
    ('Karnataka', 'Bengaluru', 'Hebbal',          'bengaluru-hebbal',          13.035500, 77.597100, FALSE, 9),
    ('Karnataka', 'Bengaluru', 'JP Nagar',        'bengaluru-jp-nagar',        12.910700, 77.585000, FALSE, 10),
    ('Karnataka', 'Bengaluru', 'Bellandur',       'bengaluru-bellandur',       12.926100, 77.678000, FALSE, 11),
    ('Karnataka', 'Bengaluru', 'Rajajinagar',     'bengaluru-rajajinagar',     12.991500, 77.552100, FALSE, 12),
    ('Karnataka', 'Bengaluru', 'Banashankari',    'bengaluru-banashankari',    12.925000, 77.546800, FALSE, 13),
    ('Karnataka', 'Bengaluru', 'Yelahanka',       'bengaluru-yelahanka',       13.100700, 77.596300, FALSE, 14)
ON CONFLICT (slug) DO NOTHING;

-- Other metros: a representative set each --------------------------------------------
-- Deliberately shallow. Depth follows demand; seeding hundreds of localities in
-- cities we have no tutors in creates empty pages, which is an SEO liability
-- (see M2-08.4 — thin pages must be noindex).
INSERT INTO locations (state, city, locality, slug, latitude, longitude, is_city, display_order) VALUES
    ('Maharashtra', 'Mumbai', 'Andheri',      'mumbai-andheri',      19.119700, 72.847900, FALSE, 1),
    ('Maharashtra', 'Mumbai', 'Bandra',       'mumbai-bandra',       19.054500, 72.840200, FALSE, 2),
    ('Maharashtra', 'Mumbai', 'Powai',        'mumbai-powai',        19.116000, 72.905100, FALSE, 3),
    ('Maharashtra', 'Mumbai', 'Thane',        'mumbai-thane',        19.218300, 72.978100, FALSE, 4),
    ('Maharashtra', 'Mumbai', 'Borivali',     'mumbai-borivali',     19.229500, 72.857600, FALSE, 5),
    ('Delhi', 'New Delhi', 'Dwarka',          'delhi-dwarka',        28.592300, 77.046000, FALSE, 1),
    ('Delhi', 'New Delhi', 'Rohini',          'delhi-rohini',        28.743200, 77.067500, FALSE, 2),
    ('Delhi', 'New Delhi', 'Saket',           'delhi-saket',         28.522800, 77.206100, FALSE, 3),
    ('Delhi', 'New Delhi', 'Laxmi Nagar',     'delhi-laxmi-nagar',   28.630700, 77.277300, FALSE, 4),
    ('Maharashtra', 'Pune', 'Kothrud',        'pune-kothrud',        18.507400, 73.807700, FALSE, 1),
    ('Maharashtra', 'Pune', 'Hinjewadi',      'pune-hinjewadi',      18.591300, 73.738900, FALSE, 2),
    ('Maharashtra', 'Pune', 'Viman Nagar',    'pune-viman-nagar',    18.567000, 73.915000, FALSE, 3),
    ('Tamil Nadu', 'Chennai', 'Adyar',        'chennai-adyar',       13.006700, 80.257400, FALSE, 1),
    ('Tamil Nadu', 'Chennai', 'Velachery',    'chennai-velachery',   12.975300, 80.220700, FALSE, 2),
    ('Tamil Nadu', 'Chennai', 'Anna Nagar',   'chennai-anna-nagar',  13.086200, 80.216800, FALSE, 3),
    ('West Bengal', 'Kolkata', 'Salt Lake',   'kolkata-salt-lake',   22.580800, 88.417400, FALSE, 1),
    ('West Bengal', 'Kolkata', 'Ballygunge',  'kolkata-ballygunge',  22.526600, 88.365800, FALSE, 2),
    ('Gujarat', 'Ahmedabad', 'Satellite',     'ahmedabad-satellite', 23.030000, 72.512000, FALSE, 1),
    ('Gujarat', 'Ahmedabad', 'Bopal',         'ahmedabad-bopal',     23.031500, 72.470900, FALSE, 2)
ON CONFLICT (slug) DO NOTHING;
