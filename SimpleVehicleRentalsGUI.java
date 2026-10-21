import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

public class AllInOneVehicleRentalsApp_EnhancedUI {

    // -------- CONFIG ----------
    private static final String DB_URL = "jdbc:mysql://localhost:3306/vehicle_rentals?useSSL=false&serverTimezone=UTC";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "Mathavu";

    // UI Colors
    private static final Color BG_COLOR = new Color(245, 248, 255);
    private static final Color FORM_BG = Color.WHITE;
    private static final Color ACCENT = new Color(20, 90, 160);
    private static final Color BUTTON_DARK = new Color(80, 80, 80);

    private static final Font DEFAULT_FONT = new Font("Segoe UI", Font.PLAIN, 14);
    private static final Font TITLE_FONT = new Font("Segoe UI", Font.BOLD, 20);

    // -------- ENTRYPOINT ----------
    public static void main(String[] args) {
        DBUtil.initDriver();
        SwingUtilities.invokeLater(LoginFrame::new);
    }

    // -------- Utilities & DB Access ----------
    static class DBUtil {
        static void initDriver() {
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException ex) {
                System.err.println("MySQL JDBC driver not found. Add mysql-connector-java to classpath.");
            }
        }
        static Connection getConnection() throws SQLException {
            return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
        }
    }

    static class PasswordUtils {
        private static final SecureRandom RANDOM = new SecureRandom();
        static String generateSaltHex() {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            return bytesToHex(salt);
        }
        static String hashPassword(String password, String saltHex) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(hexToBytes(saltHex));
                md.update(password.getBytes("UTF-8"));
                return bytesToHex(md.digest());
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        static boolean verifyPassword(String password, String saltHex, String expectedHashHex) {
            return hashPassword(password, saltHex).equalsIgnoreCase(expectedHashHex);
        }
        static String bytesToHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for(byte b: bytes) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        }
        static byte[] hexToBytes(String hex) {
            byte[] out = new byte[hex.length()/2];
            for(int i=0;i<hex.length();i+=2){
                out[i/2] = (byte)((Character.digit(hex.charAt(i),16)<<4)+Character.digit(hex.charAt(i+1),16));
            }
            return out;
        }
    }

    static class UserDAO {
        static boolean registerUser(String username,String email,String password) throws SQLException {
            String checkSql = "SELECT user_id FROM users WHERE username=? OR email=?";
            try(Connection conn=DBUtil.getConnection(); PreparedStatement pst=conn.prepareStatement(checkSql)) {
                pst.setString(1,username); pst.setString(2,email);
                try(ResultSet rs=pst.executeQuery()){ if(rs.next()) return false; }
            }
            String salt = PasswordUtils.generateSaltHex();
            String hash = PasswordUtils.hashPassword(password,salt);
            String insertSql = "INSERT INTO users (username,email,password_hash,salt) VALUES (?,?,?,?)";
            try(Connection conn=DBUtil.getConnection(); PreparedStatement pst=conn.prepareStatement(insertSql)) {
                pst.setString(1,username); pst.setString(2,email); pst.setString(3,hash); pst.setString(4,salt);
                return pst.executeUpdate()==1;
            }
        }
        static int authenticate(String userOrEmail, String password) throws SQLException {
            String sql = "SELECT user_id,password_hash,salt FROM users WHERE username=? OR email=?";
            try(Connection conn=DBUtil.getConnection(); PreparedStatement pst=conn.prepareStatement(sql)) {
                pst.setString(1,userOrEmail); pst.setString(2,userOrEmail);
                try(ResultSet rs=pst.executeQuery()) {
                    if(rs.next()) {
                        if(PasswordUtils.verifyPassword(password,rs.getString("salt"),rs.getString("password_hash")))
                            return rs.getInt("user_id");
                    }
                }
            }
            return -1;
        }
    }

    static class VehicleDAO {
        static class Vehicle {
            int id; String name,type; double rate;
            Vehicle(int id,String name,String type,double rate){this.id=id;this.name=name;this.type=type;this.rate=rate;}
        }
        static List<Vehicle> listVehiclesByType(String type) throws SQLException {
            List<Vehicle> out = new ArrayList<>();
            String sql="SELECT vehicle_id,vehicle_name,vehicle_type,rate_per_day FROM vehicles WHERE vehicle_type=? ORDER BY vehicle_name";
            try(Connection conn=DBUtil.getConnection(); PreparedStatement pst=conn.prepareStatement(sql)) {
                pst.setString(1,type);
                try(ResultSet rs=pst.executeQuery()){
                    while(rs.next()) out.add(new Vehicle(rs.getInt(1),rs.getString(2),rs.getString(3),rs.getDouble(4)));
                }
            }
            return out;
        }
        static boolean isVehicleAvailable(Connection conn,int vehicleId, LocalDate start,int days) throws SQLException {
            LocalDate end = start.plusDays(days);
            String sql = "SELECT 1 FROM rentals WHERE vehicle_id=? AND rental_date<? AND DATE_ADD(rental_date, INTERVAL duration_days DAY)>? LIMIT 1";
            try(PreparedStatement pst=conn.prepareStatement(sql)){
                pst.setInt(1,vehicleId); pst.setDate(2,Date.valueOf(end)); pst.setDate(3,Date.valueOf(start));
                try(ResultSet rs=pst.executeQuery()){ return !rs.next(); }
            }
        }
        static boolean createRental(int userId,int vehicleId,LocalDate start,int days) throws SQLException {
            try(Connection conn=DBUtil.getConnection()) {
                conn.setAutoCommit(false);
                try{
                    if(!isVehicleAvailable(conn,vehicleId,start,days)){conn.rollback(); return false;}
                    String sql="INSERT INTO rentals(user_id,vehicle_id,rental_date,duration_days) VALUES(?,?,?,?)";
                    try(PreparedStatement pst=conn.prepareStatement(sql)){
                        pst.setInt(1,userId); pst.setInt(2,vehicleId); pst.setDate(3,Date.valueOf(start)); pst.setInt(4,days);
                        pst.executeUpdate();
                    }
                    conn.commit(); return true;
                } catch(SQLException ex){conn.rollback(); throw ex;}
                finally{conn.setAutoCommit(true);}
            }
        }
        static List<String[]> getUserRentals(int userId) throws SQLException {
            List<String[]> out = new ArrayList<>();
            String sql="SELECT r.rental_id,v.vehicle_name,v.vehicle_type,r.rental_date,r.duration_days " +
                    "FROM rentals r JOIN vehicles v ON r.vehicle_id=v.vehicle_id WHERE r.user_id=? ORDER BY r.rental_date DESC";
            try(Connection conn=DBUtil.getConnection(); PreparedStatement pst=conn.prepareStatement(sql)){
                pst.setInt(1,userId);
                try(ResultSet rs=pst.executeQuery()){
                    while(rs.next()){
                        out.add(new String[]{String.valueOf(rs.getInt(1)), rs.getString(2), rs.getString(3),
                                rs.getDate(4).toString(), String.valueOf(rs.getInt(5))});
                    }
                }
            }
            return out;
        }
        static boolean cancelRental(int rentalId,int userId) throws SQLException {
            String sql="DELETE FROM rentals WHERE rental_id=? AND user_id=?";
            try(Connection conn=DBUtil.getConnection(); PreparedStatement pst=conn.prepareStatement(sql)){
                pst.setInt(1,rentalId); pst.setInt(2,userId); return pst.executeUpdate()==1;
            }
        }
    }

    // -------- GUI Helpers ----------
    private static JButton makeButton(String text, Color color){
        JButton b = new JButton(text);
        b.setFocusPainted(false);
        b.setBackground(color);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("Segoe UI", Font.BOLD, 13));
        b.setBorder(new EmptyBorder(8,18,8,18));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter(){
            public void mouseEntered(MouseEvent e){b.setBackground(color.darker());}
            public void mouseExited(MouseEvent e){b.setBackground(color);}
        });
        return b;
    }

    static class GradientPanel extends JPanel {
        public void paintComponent(Graphics g){
            super.paintComponent(g);
            Graphics2D g2=(Graphics2D)g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            GradientPaint gp = new GradientPaint(0,0,BG_COLOR,0,getHeight(),Color.WHITE);
            g2.setPaint(gp); g2.fillRect(0,0,getWidth(),getHeight());
        }
    }

    // -------- LOGIN FRAME ----------
    static class LoginFrame extends JFrame{
        private JTextField userField;
        private JPasswordField passField;
        LoginFrame(){
            setTitle("Vehicle Rentals - Login");
            setSize(480,300); setLocationRelativeTo(null); setDefaultCloseOperation(EXIT_ON_CLOSE);

            GradientPanel panel = new GradientPanel(); panel.setLayout(new BorderLayout(10,10));
            panel.setBorder(BorderFactory.createEmptyBorder(20,30,20,30));

            JLabel title = new JLabel("Vehicle Rental System",SwingConstants.CENTER);
            title.setFont(TITLE_FONT); title.setForeground(ACCENT);
            panel.add(title,BorderLayout.NORTH);

            JPanel form = new JPanel(new GridBagLayout()); form.setOpaque(false);
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets=new Insets(10,10,10,10); gbc.fill=GridBagConstraints.HORIZONTAL;

            gbc.gridx=0; gbc.gridy=0; form.add(new JLabel("Username or Email:"),gbc);
            gbc.gridx=1; userField=new JTextField(18); form.add(userField,gbc);

            gbc.gridx=0; gbc.gridy++; form.add(new JLabel("Password:"),gbc);
            gbc.gridx=1; passField=new JPasswordField(18); form.add(passField,gbc);

            gbc.gridx=0; gbc.gridy++; gbc.gridwidth=2;
            JPanel btnPanel = new JPanel(); btnPanel.setOpaque(false);
            JButton loginBtn = makeButton("Login",ACCENT);
            JButton regBtn = makeButton("Register",BUTTON_DARK);
            btnPanel.add(loginBtn); btnPanel.add(regBtn);
            form.add(btnPanel,gbc);

            panel.add(form,BorderLayout.CENTER); add(panel);

            loginBtn.addActionListener(e->doLogin());
            regBtn.addActionListener(e->new RegisterDialog(this));

            setVisible(true);
        }

        private void doLogin(){
            String user = userField.getText().trim();
            String pw = new String(passField.getPassword());
            if(user.isEmpty()||pw.isEmpty()){ JOptionPane.showMessageDialog(this,"Enter credentials."); return;}
            try{
                int userId = UserDAO.authenticate(user,pw);
                if(userId>0){ new MainFrame(userId,user); dispose();}
                else JOptionPane.showMessageDialog(this,"Invalid credentials.");
            }catch(SQLException ex){ex.printStackTrace(); JOptionPane.showMessageDialog(this,"DB Error: "+ex.getMessage());}
        }
    }

    // -------- REGISTER DIALOG ----------
    static class RegisterDialog extends JDialog{
        private JTextField usernameField,emailField;
        private JPasswordField passwordField,confirmField;
        RegisterDialog(Frame owner){
            super(owner,"Register - Vehicle Rentals",true);
            setSize(520,380); setLocationRelativeTo(owner);

            GradientPanel panel = new GradientPanel(); panel.setLayout(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints(); gbc.insets=new Insets(8,8,8,8); gbc.fill=GridBagConstraints.HORIZONTAL;

            usernameField = new JTextField(24);
            emailField = new JTextField(24);
            passwordField = new JPasswordField(24);
            confirmField = new JPasswordField(24);

            gbc.gridx=0; gbc.gridy=0; panel.add(new JLabel("Username:"),gbc);
            gbc.gridx=1; panel.add(usernameField,gbc);
            gbc.gridx=0; gbc.gridy++; panel.add(new JLabel("Email:"),gbc);
            gbc.gridx=1; panel.add(emailField,gbc);
            gbc.gridx=0; gbc.gridy++; panel.add(new JLabel("Password:"),gbc);
            gbc.gridx=1; panel.add(passwordField,gbc);
            gbc.gridx=0; gbc.gridy++; panel.add(new JLabel("Confirm:"),gbc);
            gbc.gridx=1; panel.add(confirmField,gbc);

            gbc.gridx=0; gbc.gridy++; gbc.gridwidth=2; gbc.anchor=GridBagConstraints.CENTER;
            JPanel btnPanel = new JPanel(); btnPanel.setOpaque(false);
            JButton registerBtn = makeButton("Register",ACCENT);
            JButton cancelBtn = makeButton("Cancel",BUTTON_DARK);
            btnPanel.add(registerBtn); btnPanel.add(cancelBtn);
            panel.add(btnPanel,gbc);

            add(panel);

            registerBtn.addActionListener(e->doRegister());
            cancelBtn.addActionListener(e->dispose());
            setVisible(true);
        }

        private void doRegister(){
            String username=usernameField.getText().trim();
            String email=emailField.getText().trim();
            String pw=new String(passwordField.getPassword());
            String cpw=new String(confirmField.getPassword());
            if(username.isEmpty()||email.isEmpty()||pw.isEmpty()){JOptionPane.showMessageDialog(this,"Fill all fields."); return;}
            if(!pw.equals(cpw)){JOptionPane.showMessageDialog(this,"Passwords do not match."); return;}
            try{
                boolean ok=UserDAO.registerUser(username,email,pw);
                if(ok){JOptionPane.showMessageDialog(this,"Registered successfully."); dispose();}
                else JOptionPane.showMessageDialog(this,"Username or email already exists.");
            }catch(SQLException ex){ex.printStackTrace(); JOptionPane.showMessageDialog(this,"DB Error: "+ex.getMessage());}
        }
    }

    // -------- MAIN FRAME ----------
    static class MainFrame extends JFrame {
        private int userId;
        private String username;
        private JTable rentalTable;
        private DefaultTableModel rentalModel;
        MainFrame(int userId,String username){
            this.userId=userId; this.username=username;
            setTitle("Vehicle Rentals - Welcome "+username);
            setSize(800,500); setLocationRelativeTo(null); setDefaultCloseOperation(EXIT_ON_CLOSE);

            GradientPanel panel = new GradientPanel(); panel.setLayout(new BorderLayout(10,10));
            panel.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));

            JLabel title = new JLabel("Your Rentals",SwingConstants.CENTER);
            title.setFont(TITLE_FONT); title.setForeground(ACCENT);
            panel.add(title,BorderLayout.NORTH);

            rentalModel = new DefaultTableModel(new String[]{"ID","Vehicle","Type","Date","Duration"},0);
            rentalTable = new JTable(rentalModel);
            rentalTable.setFillsViewportHeight(true);
            JScrollPane scroll = new JScrollPane(rentalTable);
            panel.add(scroll,BorderLayout.CENTER);

            JButton refreshBtn = makeButton("Refresh",ACCENT);
            JButton cancelBtn = makeButton("Cancel Rental",BUTTON_DARK);
            JPanel btnPanel = new JPanel(); btnPanel.setOpaque(false);
            btnPanel.add(refreshBtn); btnPanel.add(cancelBtn);
            panel.add(btnPanel,BorderLayout.SOUTH);

            refreshBtn.addActionListener(e->loadRentals());
            cancelBtn.addActionListener(e->cancelSelectedRental());

            add(panel);
            loadRentals();
            setVisible(true);
        }

        private void loadRentals(){
            try{
                rentalModel.setRowCount(0);
                List<String[]> rentals = VehicleDAO.getUserRentals(userId);
                for(String[] r: rentals) rentalModel.addRow(r);
            }catch(SQLException ex){ex.printStackTrace();}
        }

        private void cancelSelectedRental(){
            int row = rentalTable.getSelectedRow();
            if(row<0){JOptionPane.showMessageDialog(this,"Select a rental."); return;}
            int rentalId=Integer.parseInt(rentalModel.getValueAt(row,0).toString());
            try{
                boolean ok=VehicleDAO.cancelRental(rentalId,userId);
                if(ok){JOptionPane.showMessageDialog(this,"Rental cancelled."); loadRentals();}
                else JOptionPane.showMessageDialog(this,"Failed to cancel rental.");
            }catch(SQLException ex){ex.printStackTrace();}
        }
    }
}