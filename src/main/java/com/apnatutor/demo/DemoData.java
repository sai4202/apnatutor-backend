package com.apnatutor.demo;

import java.util.List;

/**
 * The cast — {@code M6-04.1}.
 *
 * <h2>Why the data is written out rather than generated</h2>
 *
 * <p>A generator produces "Tutor 47 teaches Subject 3 in Locality 9", which is enough to exercise a
 * query and useless for looking at a screen. Demo data exists to answer "does this look like a
 * marketplace a parent would trust", and that question is only answerable with names, fees and
 * headlines that read like real ones. Fifteen hand-written tutors beat five hundred generated ones.
 *
 * <p>Names, fees and localities are plausible for the launch city. Fees are in paise and sit in the
 * range Hyderabad tuition actually occupies, because a demo where everything costs ₹500/month
 * teaches the wrong thing about the pricing bands.
 */
final class DemoData {

	private DemoData() {
	}

	/**
	 * @param subjectSlugs what they teach; the first is their headline subject
	 * @param localitySlugs where they teach; empty means online only
	 * @param feeMinPaise monthly fee floor
	 */
	record DemoTutor(
			String phone,
			String displayName,
			String headline,
			String bio,
			int experienceYears,
			long feeMinPaise,
			long feeMaxPaise,
			List<String> subjectSlugs,
			List<String> localitySlugs,
			List<String> teachingModes,
			boolean idVerified,
			boolean offersDemo) {
	}

	/** @param subjectSlug what the student wants taught */
	record DemoRequirement(
			String studentPhone,
			String studentName,
			String subjectSlug,
			String gradeSlug,
			String boardSlug,
			String localitySlug,
			String mode,
			long budgetPaise,
			String description) {
	}

	/** @param tutorPhone who is being reviewed; the student must have been connected to them */
	record DemoReview(String tutorPhone, String studentPhone, int rating, String title, String body) {
	}

	static final List<DemoTutor> TUTORS = List.of(
			new DemoTutor("+919000000101", "Lakshmi Narayanan",
					"IIT-M alumnus | JEE & Class 11-12 Maths for 12 years",
					"I teach the reasoning before the shortcut. Most students arriving from school "
							+ "have learnt to recognise question types rather than to think about "
							+ "them, and that stops working somewhere around JEE Main. We spend the "
							+ "first month undoing that. I keep batches under six.",
					12, 800_000L, 1_200_000L,
					List.of("mathematics", "jee-main"),
					List.of("hyderabad-gachibowli", "hyderabad-madhapur"),
					List.of("STUDENT_HOME", "ONLINE"), true, true),

			new DemoTutor("+919000000102", "Priya Sharma",
					"NEET Biology | 400+ students, 9 years",
					"Biology rewards memory less than people think. I teach the diagrams and the "
							+ "sequences first, because once a student can draw the process they "
							+ "stop needing to memorise the paragraph about it. Weekly tests from "
							+ "month two, and I mark them myself.",
					9, 700_000L, 1_000_000L,
					List.of("biology", "neet"),
					List.of("hyderabad-kondapur", "hyderabad-hitec-city"),
					List.of("STUDENT_HOME", "TUTOR_PLACE"), true, true),

			new DemoTutor("+919000000103", "Rahul Verma",
					"Physics for Class 11-12 and JEE | ex-FIITJEE",
					"Eight years in a coaching institute taught me what large batches cannot fix: "
							+ "students who can apply a formula but cannot say what it means. I work "
							+ "one to one and start every topic with the experiment it came from.",
					8, 900_000L, 1_400_000L,
					List.of("physics", "jee-advanced"),
					List.of("hyderabad-kukatpally"),
					List.of("STUDENT_HOME", "ONLINE"), true, false),

			new DemoTutor("+919000000104", "Anjali Reddy",
					"Class 6-10 Maths & Science | CBSE and State Board",
					"I work with students who have decided they are bad at maths. They are usually "
							+ "not; they are missing one thing from two years ago. Finding it takes a "
							+ "few sessions and changes everything after it. Patient, and happy to "
							+ "go slowly.",
					6, 400_000L, 600_000L,
					List.of("mathematics", "science"),
					List.of("hyderabad-miyapur", "hyderabad-kukatpally"),
					List.of("STUDENT_HOME"), true, true),

			new DemoTutor("+919000000105", "Mohammed Irfan",
					"Spoken English & IELTS | British Council certified",
					"Fluency is a habit, not a vocabulary list. My sessions are conversation from "
							+ "the first day, corrected as we go rather than afterwards. I work with "
							+ "a lot of working professionals, so early mornings and late evenings "
							+ "are fine.",
					7, 500_000L, 800_000L,
					List.of("spoken-english", "ielts"),
					List.of(),
					List.of("ONLINE"), true, true),

			new DemoTutor("+919000000106", "Sneha Iyer",
					"Chemistry | Class 11-12, NEET and JEE",
					"Organic chemistry is where most students give up, and it is the most learnable "
							+ "part of the syllabus once you see it as a small number of mechanisms "
							+ "rather than a thousand reactions. That is what I teach first.",
					5, 600_000L, 900_000L,
					List.of("chemistry", "neet"),
					List.of("hyderabad-gachibowli"),
					List.of("STUDENT_HOME", "ONLINE"), false, true),

			new DemoTutor("+919000000107", "Vikram Singh",
					"Python & Web Development | working software engineer",
					"I build software for a living and teach in the evenings, which means the "
							+ "projects we work on are ones people actually ship. Good for school "
							+ "students doing computer science and for adults changing career.",
					4, 700_000L, 1_100_000L,
					List.of("python", "web-development"),
					List.of(),
					List.of("ONLINE"), true, true),

			new DemoTutor("+919000000108", "Kavitha Rao",
					"Carnatic vocal | 15 years teaching, performing since 2004",
					"Beginners to intermediate, all ages. We start with the voice rather than the "
							+ "theory, because a student who enjoys the sound they make keeps "
							+ "practising and one who does not stops within a term.",
					15, 300_000L, 500_000L,
					List.of("carnatic-vocal"),
					List.of("hyderabad-ameerpet"),
					List.of("TUTOR_PLACE", "ONLINE"), false, true));

	static final List<DemoRequirement> REQUIREMENTS = List.of(
			new DemoRequirement("+919000000201", "Sunitha Rao", "mathematics", "class-10", "cbse",
					"hyderabad-gachibowli", "STUDENT_HOME", 600_000L,
					"My daughter is in Class 10 CBSE and has gone from scoring 80s to the low 60s "
							+ "since Class 9. She says she understands in class and then cannot do "
							+ "the sums at home. Looking for someone patient, three days a week, "
							+ "preferably evenings after 6."),

			new DemoRequirement("+919000000202", "Ganesh Kumar", "neet", "class-12", "state-telangana",
					"hyderabad-kukatpally", "STUDENT_HOME", 1_000_000L,
					"NEET 2027 attempt. Son is in Class 12, scoring around 480 in mock tests, "
							+ "weakest in Biology. Needs someone who can push him without making "
							+ "him anxious - he takes pressure badly."),

			new DemoRequirement("+919000000203", "Fatima Begum", "spoken-english", "adult",
					null, null, "ONLINE", 500_000L,
					"I work in a customer-facing role and my written English is fine but I freeze "
							+ "on calls. Looking for conversation practice rather than grammar "
							+ "classes. Early mornings before 8am work best for me."),

			new DemoRequirement("+919000000204", "Ravi Teja", "physics", "class-11", "cbse",
					"hyderabad-madhapur", "ONLINE", 900_000L,
					"Class 11 Physics, CBSE, targeting JEE. Online is fine and probably better - we "
							+ "are in Madhapur and traffic makes evening travel painful. Two hours "
							+ "twice a week."),

			new DemoRequirement("+919000000205", "Meera Joshi", "python", "adult", null,
					null, "ONLINE", 800_000L,
					"Career change from finance into data work. I have done a couple of online "
							+ "courses and can write basic scripts, but I have never built anything "
							+ "end to end and I think that is the gap. Weekends only."),

			new DemoRequirement("+919000000206", "Arun Prasad", "chemistry", "class-12", "cbse",
					"hyderabad-kondapur", "STUDENT_HOME", 700_000L,
					"Class 12 Chemistry. Organic is the problem - he can do physical and inorganic "
							+ "but organic has never clicked. Board exams in four months."));

	static final List<DemoReview> REVIEWS = List.of(
			new DemoReview("+919000000101", "+919000000201", 5,
					"She finally likes maths again",
					"Three months in and my daughter's last test was 84. What I did not expect was "
							+ "that she now does the extra sums without being asked. He found that "
							+ "she had never properly understood factorisation from Class 9 and went "
							+ "back to it, which nobody at school had picked up."),

			new DemoReview("+919000000102", "+919000000202", 5,
					"Calm, organised, and my son trusts her",
					"We had tried two tutors before who both taught by making him feel behind. "
							+ "Priya does the opposite. His Biology mock score has gone from 240 to "
							+ "310 and, more importantly, he has stopped dreading the subject."),

			new DemoReview("+919000000103", "+919000000204", 4,
					"Strong teacher, scheduling was hard at first",
					"Genuinely excellent on the physics - he explains why a formula exists rather "
							+ "than handing it over, which is what we wanted. Only reason this is "
							+ "four stars is that the first month had a lot of rescheduling. Settled "
							+ "down since."),

			new DemoReview("+919000000105", "+919000000203", 5,
					"I can take calls now without rehearsing",
					"Six weeks of half-hour sessions before work. He corrects in the moment rather "
							+ "than saving it for the end, which felt uncomfortable for about two "
							+ "sessions and then became the useful part."),

			new DemoReview("+919000000106", "+919000000206", 4,
					"Organic finally makes sense",
					"My son's words: 'it's just the same five things happening in different orders'. "
							+ "That is more than a year of school produced. Half a star off only "
							+ "because we would have liked more written practice."));
}
