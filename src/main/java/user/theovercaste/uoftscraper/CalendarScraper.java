package user.theovercaste.uoftscraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CalendarScraper {
    private static final Pattern courseIdPattern = Pattern.compile("\\w{3}\\d{3}(H|Y)1");
    private static final Pattern courseHeaderPattern = Pattern.compile("(.*?)([\\s\\xA0]+)(.*)\\[(.*?)\\]");
    private static final Pattern courseBreadthPattern = Pattern.compile("Breadth Requirement: (.*?)\\((.)\\)");

    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://localhost/UOfTScraped", "root", "");
    }


    private static void initDatabase() throws SQLException {
        Connection con = DriverManager.getConnection("jdbc:mysql://localhost", "root", "");
        Statement st = con.createStatement();
        st.execute("CREATE DATABASE IF NOT EXISTS UOfTScraped");
        st.close();
        st = con.createStatement();
        st.execute("USE UOfTScraped");
        st.close();
        st = con.createStatement();
        st.addBatch("CREATE TABLE IF NOT EXISTS Program (url varchar(255) PRIMARY KEY)");
        st.addBatch("CREATE TABLE IF NOT EXISTS Course (" +
                "uid INT AUTO_INCREMENT PRIMARY KEY NOT NULL," +
                "identifier varchar(20) NOT NULL, " +
                "name varchar(128) NOT NULL, " +
                "description TEXT, " +
                "prerequisite varchar(256), " +
                "exclusion varchar(256), " +
                "breadth INT, " +
                "UNIQUE(identifier)" +
                ")");
        st.addBatch("CREATE TABLE IF NOT EXISTS Course_Meeting_Section (" +
                "uid INT AUTO_INCREMENT PRIMARY KEY NOT NULL, " +
                "course_id INT NOT NULL, " +
                "section_id INT NOT NULL, " +
                "online BOOLEAN NOT NULL, " +
                "instructor varchar(128), " +
                "section_type INT NOT NULL, " +
                "FOREIGN KEY(course_id) REFERENCES Course(uid) ON DELETE CASCADE" +
                ")");
        st.executeBatch();
        st.close();
        st = con.createStatement();
        st.addBatch("CREATE TABLE IF NOT EXISTS Course_Meeting_Section_Time (" +
                "course_meeting_section int NOT NULL, " +
                "day_of_week int NOT NULL, " +
                "start_time_hour int NOT NULL, " +
                "start_time_minute int NOT NULL, " +
                "length_minutes int NOT NULL, " +
                "FOREIGN KEY(course_meeting_section) REFERENCES Course_Meeting_Section(uid) ON DELETE CASCADE" +
                ")");
        st.executeBatch();
        st.close();
        con.close();
    }

    public static List<String> getCalendarUrls() throws IOException {
        List<String> ret = new ArrayList<>();
        Document doc = Jsoup.connect("http://www.artsandscience.utoronto.ca/ofr/calendar").get();
        for (Element items : doc.getElementsByClass("items")) {
            Element ul = items.getAllElements().get(0); //the inner list
            for (Element li : ul.getAllElements()) {
                Element a = li.getAllElements().get(0);
                ret.add("http://www.artsandscience.utoronto.ca/ofr/calendar/" + a.attr("href"));
            }
        }
        return ret;
    }

    private static void scrapeTimetable() throws Exception {
        initDatabase();
        Document doc = Jsoup.connect("http://www.artsandscience.utoronto.ca/ofr/timetable/winter/csc.html").get();
        PreparedStatement sectionPsmt = getConnection().prepareStatement("INSERT IGNORE INTO Course_Meeting_Section(course_id, section_id, online, instructor, section_type) VALUES (?,?,?,?,?)");
        PreparedStatement timePsmt = getConnection().prepareStatement("INSERT IGNORE INTO Course_Meeting_Section_Time(course_meeting_section, day_of_week, start_time_hour, start_time_minute, length_minutes) VALUES (?,?,?,?,?)");
        Element table = doc.getElementsByTag("tbody").get(0);
        int i = 0;
        String course = "";
        String sectionCode = "";
        int sectionType = 0;
        String meetingSection = "";
        String instructor = "";
        boolean online = false;
        String timeString = null;
        for (Element row : table.children()) {
            i++;
            if (i == 1 || i == 2) {
                continue;
            }
            int j = 0;
            for (Element column : row.children()) {
                if (column.getElementsByTag("font").size() == 0) {
                    continue;
                }
                String text = column.getElementsByTag("font").get(0).text().replace("\u00a0", "").trim(); //remove no break spaces, they're everywhere!
                if (!text.isEmpty()) {
                    if (j == 0) {
                        course = text;
                    } else if (j == 1) {
                        sectionCode = text;
                    } else if (j == 3) {
                        switch (text.charAt(0)) {
                            case 'L':
                                sectionType = 0;
                                break;
                            case 'P':
                                sectionType = 1;
                                break;
                            case 'T':
                                sectionType = 2;
                                break;
                        }
                        meetingSection = text.substring(1);
                    } else if (j == 5) {
                        if(text.equals("ONLINE")) {
                            online = true;
                            timeString = null;
                        } else {
                            online = false;
                            timeString = text;
                        }
                    } else if (j == 7) {
                        instructor = text;
                    }
                }
                j++;
            }
            System.out.println("Row " + i + " data: " + course + ", " + sectionCode + ", " + sectionType + ", " + meetingSection + ", " + instructor);
        }
    }

    private static void scrapeCourses() throws Exception {
        initDatabase();
        Document doc = Jsoup.connect("http://www.artsandscience.utoronto.ca/ofr/calendar/crs_csc.htm").get();
        PreparedStatement pst = getConnection().prepareStatement("INSERT IGNORE INTO Course(identifier,name,description,prerequisite,exclusion,breadth) VALUES (?,?,?,?,?,?)");
        for (Element e : doc.getElementsByTag("a")) {
            if (!e.hasAttr("name")) {
                continue;
            }
            if (courseIdPattern.matcher(e.attr("name")).matches()) {
                Element header = e.nextElementSibling();
                if (header == null) {
                    continue;
                }
                Element description = header.nextElementSibling();
                if (description == null) {
                    continue;
                }
                Matcher m = courseHeaderPattern.matcher(header.text());
                if (!m.find()) {
                    continue;
                }
                Node ei = description;
                String courseId = m.group(1);
                String courseName = m.group(3);
                StringBuilder descriptionText = new StringBuilder(description.text());
                StringBuilder prerequisite = new StringBuilder();
                StringBuilder exclusion = new StringBuilder();
                int breadthRequirement = -1;
                boolean readingDescription = true;
                boolean readingPrereq = false;
                boolean readingExclusion = false;
                while (ei.nextSibling() != null) {
                    ei = ei.nextSibling();
                    String text = "";
                    String tagName = "";
                    if (ei instanceof TextNode) {
                        text = ((TextNode) ei).text();
                    } else if (ei instanceof Element) {
                        text = ((Element) ei).text();
                        tagName = ((Element) ei).tagName();
                    }
                    System.out.println("Text: " + text + ", tag name: " + tagName);
                    if (text.startsWith("Prerequisite:")) {
                        readingPrereq = true;
                        text = text.substring("Prerequisite:".length()).trim();
                    } else if (text.startsWith("Exclusion:")) {
                        readingExclusion = true;
                        text = text.substring("Exclusion:".length()).trim();
                    } else if (text.startsWith("Breadth Requirement: ")) {
                        m = courseBreadthPattern.matcher(text);
                        System.out.println("Text: " + text);
                        if (m.find()) {
                            System.out.println("Matches!");
                            try {
                                breadthRequirement = Integer.valueOf(m.group(2));
                            } catch (NumberFormatException ex) {
                                ex.printStackTrace();
                            }
                        }
                        break;
                    }
                    if (readingPrereq || readingExclusion) {
                        if ("br".equals(tagName)) {
                            readingPrereq = false;
                            readingExclusion = false;
                        }
                        readingDescription = false;
                    }
                    if (readingDescription) {
                        descriptionText.append(text);
                    }
                    if (readingPrereq) {
                        prerequisite.append(text);
                    } else if (readingExclusion) {
                        exclusion.append(text);
                    }
                }

                System.out.println("Found course: " + courseId);
                pst.setString(1, courseId);
                pst.setString(2, courseName);
                Reader r = new StringReader(descriptionText.toString());
                pst.setCharacterStream(3, r);
                if (prerequisite.length() == 0) {
                    pst.setNull(4, Types.VARCHAR);
                } else {
                    pst.setString(4, prerequisite.toString());
                }
                if (exclusion.length() == 0) {
                    pst.setNull(5, Types.VARCHAR);
                } else {
                    pst.setString(5, exclusion.toString());
                }
                if (breadthRequirement == -1) {
                    pst.setNull(6, Types.INTEGER);
                } else {
                    pst.setInt(6, breadthRequirement);
                }
                pst.addBatch();
                r.close();
            }
        }
        pst.executeBatch();
    }

    public static void main(String[] args) throws Exception {
        scrapeTimetable();
    }
}
