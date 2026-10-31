package com.hans;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * The side panel, after docs/mockups/runelite-panel.html ("Now"): this session, last session and "Nearly there",
 * with a button that opens the Hans website. Only what the web app already has (its "Welcome back" tile).
 * RuneLite's own panel colours and the game's fonts. Every method runs on the Swing thread.
 *
 * <p>Sign-in (card #76, {@link SignInFlow}): with it switched on and a character logged in, "Open Hans" opens the
 * browser signed in, and "Sign in on phone" shows a short code and its time left. Each asks for its own one-time
 * challenge. Otherwise "Open Hans" just opens the address in the settings, as before.
 */
class RsbuddyPanel extends PluginPanel
{
	private static final Color TEXT = new Color(0xc6c6c6);
	private static final Color ORANGE = ColorScheme.BRAND_ORANGE;
	private static final Color GREEN = new Color(0x37f046);
	private static final Color BAR = new Color(0xe6961e);
	private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

	private final SkillIconManager icons;
	private final JLabel name = label("Not logged in", FontManager.getRunescapeBoldFont(), Color.WHITE);
	private final JLabel saved = label("", FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR);
	private final JPanel body = new JPanel();
	private final Timer timer;
	private final JButton open = button(OPEN);
	private final JButton phone = button(PHONE);
	private final JPanel signBox = new JPanel();
	private final SignInFlow signIn;
	private JLabel codeLeft;
	private JLabel sessionTime;
	private JLabel sessionRate;
	private PanelModel model;

	private static final String OPEN = "Open Hans";
	private static final String PHONE = "Manual link";

	RsbuddyPanel(SkillIconManager icons, Supplier<String> appUrl, SignInFlow.Requests requests)
	{
		super(false);
		this.icons = icons;
		this.signIn = new SignInFlow(requests, new SignView(), SwingUtilities::invokeLater, appUrl,
			System::currentTimeMillis);
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(8, 8, 8, 8));

		JPanel head = new JPanel(new BorderLayout());
		head.setOpaque(false);
		head.setBorder(new EmptyBorder(0, 2, 6, 2));
		head.add(name, BorderLayout.WEST);
		head.add(saved, BorderLayout.EAST);
		add(head, BorderLayout.NORTH);

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		add(body, BorderLayout.CENTER);

		open.setToolTipText("Opens the Hans website in your browser");
		open.addActionListener(e -> signIn.click(SignInFlow.Action.BROWSER, model != null && model.account != null));
		phone.setToolTipText("Shows a code for signing in on another device");
		phone.addActionListener(e -> signIn.click(SignInFlow.Action.PHONE, model != null && model.account != null));
		signBox.setLayout(new BoxLayout(signBox, BoxLayout.Y_AXIS));
		signBox.setOpaque(false);
		JPanel buttons = new JPanel(new GridLayout(2, 1, 0, 4));
		buttons.setOpaque(false);
		buttons.add(open);
		buttons.add(phone);
		JPanel foot = new JPanel(new BorderLayout());
		foot.setOpaque(false);
		foot.setBorder(new EmptyBorder(6, 0, 0, 0));
		foot.add(signBox, BorderLayout.NORTH);
		foot.add(buttons, BorderLayout.CENTER);
		add(foot, BorderLayout.SOUTH);

		timer = new Timer(1000, e -> tick());
		show(PanelModel.loggedOut(null));
	}

	void start()
	{
		timer.start();
	}

	void stop()
	{
		timer.stop();
	}

	void setLastWrite(Instant when)
	{
		saved.setText(when == null ? "" : "saved " + HHMM.format(when));
	}

	void show(PanelModel m)
	{
		model = m;
		name.setText(m.account == null ? "Not logged in" : m.account);
		body.removeAll();
		sessionTime = null;
		sessionRate = null;

		if (m.account != null && m.sessionStart != null)
		{
			JPanel box = box("This session", "");
			sessionTime = (JLabel) ((JPanel) box.getComponent(0)).getComponent(1);
			JPanel total = row(null, "+" + String.format("%,d", m.sessionXp) + " XP", "", Color.WHITE, true);
			sessionRate = (JLabel) total.getComponent(1);
			box.add(total);
			for (int i = 0; i < Math.min(3, m.gains.size()); i++)
			{
				PanelModel.Gain g = m.gains.get(i);
				box.add(row(g.skill, g.skill, "+" + String.format("%,d", g.xp), TEXT, false));
			}
			for (String up : m.levelUps)
			{
				box.add(row(null, up, "", GREEN, false));
			}
			if (m.gains.isEmpty())
			{
				box.add(row(null, "No XP gained yet", "", ColorScheme.LIGHT_GRAY_COLOR, false));
			}
			addBox(box);
		}
		if (m.lastTitle != null)
		{
			JPanel box = box("Last session", m.lastTitle);
			JLabel line = label("<html>" + m.lastLine + "</html>", FontManager.getRunescapeFont(), ColorScheme.LIGHT_GRAY_COLOR);
			box.add(line);
			addBox(box);
		}
		if (!m.goals.isEmpty())
		{
			JPanel box = box("Nearly there", "");
			for (PanelModel.Goal g : m.goals)
			{
				JPanel r = row(g.skill, g.label, g.right, TEXT, false);
				r.setToolTipText(g.tip);
				box.add(r);
				box.add(bar(g.fraction));
			}
			addBox(box);
		}
		if (m.account == null && m.lastTitle == null)
		{
			JPanel box = box("Hans", "");
			box.add(label("<html>Log in to see this session, your last session and what's nearly done.</html>",
				FontManager.getRunescapeFont(), ColorScheme.LIGHT_GRAY_COLOR));
			addBox(box);
		}
		tick();
		body.revalidate();
		body.repaint();
	}

	private void addBox(JPanel box)
	{
		if (body.getComponentCount() > 0)
		{
			body.add(Box.createVerticalStrut(6));
		}
		body.add(box);
	}

	/** Updates the live duration, XP rate and the code's time left once a second. */
	private void tick()
	{
		signIn.tick();
		if (model == null || sessionTime == null || model.sessionStart == null)
		{
			return;
		}
		Duration d = Duration.between(model.sessionStart, Instant.now());
		sessionTime.setText(PanelModel.duration(d));
		double hours = d.getSeconds() / 3600.0;
		sessionRate.setText(hours >= 0.05 && model.sessionXp > 0 ? PanelModel.shortXp(Math.round(model.sessionXp / hours)) + "/hr" : "");
	}

	// ------------------------------------------------------------------ sign-in

	/** Sign-in was switched off or changed, or the character logged out or changed: nothing waits, no code shown. */
	void signInReset()
	{
		signIn.reset();
	}

	/** Draws what {@link SignInFlow} shows. Draft wording for the owner. */
	private final class SignView implements SignInFlow.View
	{
		@Override
		public void busy(SignInFlow.Action action)
		{
			open.setEnabled(false);
			phone.setEnabled(false);
			(action == SignInFlow.Action.BROWSER ? open : phone).setText("Opening...");
		}

		@Override
		public void idle()
		{
			open.setEnabled(true);
			phone.setEnabled(true);
			open.setText(OPEN);
			phone.setText(PHONE);
		}

		@Override
		public void browse(String url)
		{
			LinkBrowser.browse(url);
		}

		@Override
		public void showCode(AuthClient.Result code, String timeLeft)
		{
			JPanel box = box("Manual sign in", "");
			box.add(label("<html>On any device, go to " + SignInFlow.shortAddress(code.loginUrl)
				+ " and type this code:</html>", FontManager.getRunescapeFont(), ColorScheme.LIGHT_GRAY_COLOR));
			JPanel line = new JPanel(new BorderLayout(6, 0));
			line.setOpaque(false);
			line.setBorder(new EmptyBorder(6, 0, 4, 0));
			line.add(label(code.code, FontManager.getRunescapeBoldFont(), Color.WHITE), BorderLayout.CENTER);
			JButton copy = new JButton("Copy");
			copy.setFont(FontManager.getRunescapeSmallFont());
			copy.setFocusPainted(false);
			copy.setToolTipText("Copy the code");
			String text = code.code;
			copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new StringSelection(text), null));
			line.add(copy, BorderLayout.EAST);
			line.setAlignmentX(LEFT_ALIGNMENT);
			line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
			box.add(line);
			codeLeft = label("", FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR);
			box.add(codeLeft);
			countdown(timeLeft);
			setSignBox(box);
		}

		@Override
		public void countdown(String timeLeft)
		{
			if (codeLeft != null)
			{
				codeLeft.setText(timeLeft);
			}
		}

		@Override
		public void message(String text)
		{
			JPanel box = box("Sign in", "");
			box.add(label("<html>" + text + "</html>", FontManager.getRunescapeFont(), ColorScheme.LIGHT_GRAY_COLOR));
			setSignBox(box);
		}

		@Override
		public void clear()
		{
			codeLeft = null;
			signBox.removeAll();
			signBox.revalidate();
			signBox.repaint();
		}

		private void setSignBox(JPanel box)
		{
			if (!box.isAncestorOf(codeLeft))
			{
				codeLeft = null;
			}
			signBox.removeAll();
			signBox.add(box);
			signBox.add(Box.createVerticalStrut(6));
			signBox.revalidate();
			signBox.repaint();
		}
	}

	private static JButton button(String text)
	{
		JButton b = new JButton(text);
		b.setFont(FontManager.getRunescapeFont());
		b.setForeground(Color.WHITE);
		b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		b.setFocusPainted(false);
		b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(6, 0, 5, 0)));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return b;
	}

	// ------------------------------------------------------------------ building blocks

	private static JLabel label(String text, Font font, Color color)
	{
		JLabel l = new JLabel(text);
		l.setFont(font);
		l.setForeground(color);
		return l;
	}

	/** A dark box with an orange title on the left and a small grey note on the right. */
	private static JPanel box(String title, String note)
	{
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setBorder(new EmptyBorder(6, 8, 6, 8));
		JPanel head = new JPanel(new BorderLayout());
		head.setOpaque(false);
		head.add(label(title, FontManager.getRunescapeBoldFont(), ORANGE), BorderLayout.WEST);
		head.add(label(note, FontManager.getRunescapeSmallFont(), ColorScheme.LIGHT_GRAY_COLOR), BorderLayout.EAST);
		head.setAlignmentX(LEFT_ALIGNMENT);
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		box.add(head);
		box.setAlignmentX(LEFT_ALIGNMENT);
		return box;
	}

	/** One line: an optional skill icon and text on the left, a value on the right. */
	private JPanel row(String skill, String text, String right, Color color, boolean bold)
	{
		JPanel r = new JPanel(new BorderLayout(6, 0));
		r.setOpaque(false);
		r.setBorder(new EmptyBorder(2, 0, 1, 0));
		JLabel left = label(text, bold ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont(), color);
		BufferedImage img = skillImage(skill);
		if (img != null)
		{
			left.setIcon(new ImageIcon(img));
			left.setIconTextGap(6);
		}
		r.add(left, BorderLayout.CENTER);
		r.add(label(right, FontManager.getRunescapeFont(), Color.WHITE), BorderLayout.EAST);
		r.setAlignmentX(LEFT_ALIGNMENT);
		r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		return r;
	}

	private BufferedImage skillImage(String skill)
	{
		if (skill == null)
		{
			return null;
		}
		for (Skill s : Skill.values())
		{
			if (s.getName().equals(skill))
			{
				return icons.getSkillImage(s, true);
			}
		}
		return null;
	}

	/** A thin progress bar in RuneLite's in-progress colour. */
	private static JComponent bar(double fraction)
	{
		final double f = Math.max(0, Math.min(1, fraction));
		JComponent c = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				g.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setColor(BAR);
				g.fillRect(0, 0, (int) Math.round(getWidth() * f), getHeight());
			}
		};
		c.setPreferredSize(new Dimension(10, 6));
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
		c.setAlignmentX(LEFT_ALIGNMENT);
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setOpaque(false);
		wrap.setBorder(new EmptyBorder(1, 0, 6, 0));
		wrap.add(c);
		wrap.setAlignmentX(LEFT_ALIGNMENT);
		wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 13));
		return wrap;
	}
}
