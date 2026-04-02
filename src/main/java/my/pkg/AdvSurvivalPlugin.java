package my.pkg;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AdvSurvivalPlugin extends JavaPlugin implements Listener {

    private static final String TEAM_NAME = "adv_survival_team";

    // 협동모드 진행 여부
    private boolean running = false;

    // 참가자
    private final Set<UUID> participants = new HashSet<>();
    private final Map<UUID, String> participantNames = new HashMap<>();

    // 시작 시 감지한 플레이어 수(고정)
    private int participantCount = 0;

    // 팀 공용 시간
    private int remainingTime = 0;
    private int maxTime = 0;

    // 보스바 / 타이머
    private BossBar bossBar;
    private BukkitTask timerTask;

    @Override
    public void onEnable() {
        // config.yml 안 쓸 거면 saveDefaultConfig() 넣지 마
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("AdvSurvivalPlugin enabled.");
    }

    @Override
    public void onDisable() {
        stopGame(false, null);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("advsurvival")) {
            return false;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "/advsurvival start");
            sender.sendMessage(ChatColor.YELLOW + "/advsurvival stop");
            sender.sendMessage(ChatColor.YELLOW + "/advsurvival time");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> {
                startGame(sender);
                return true;
            }
            case "stop" -> {
                if (!running) {
                    sender.sendMessage(ChatColor.RED + "현재 게임이 진행 중이 아닙니다.");
                    return true;
                }
                stopGame(true, sender);
                return true;
            }
            case "time" -> {
                if (!running) {
                    sender.sendMessage(ChatColor.RED + "현재 게임이 진행 중이 아닙니다.");
                    return true;
                }
                sender.sendMessage(ChatColor.AQUA + "현재 팀 공용 남은 시간: "
                        + ChatColor.WHITE + remainingTime + "초");
                return true;
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "알 수 없는 명령어입니다.");
                sender.sendMessage(ChatColor.YELLOW + "/advsurvival start");
                sender.sendMessage(ChatColor.YELLOW + "/advsurvival stop");
                sender.sendMessage(ChatColor.YELLOW + "/advsurvival time");
                return true;
            }
        }
    }

    private void startGame(CommandSender sender) {
        if (running) {
            sender.sendMessage(ChatColor.RED + "이미 도전과제 생존이 진행 중입니다.");
            return;
        }

        participants.clear();
        participantNames.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isParticipant(player)) {
                participants.add(player.getUniqueId());
                participantNames.put(player.getUniqueId(), player.getName());
            }
        }

        participantCount = participants.size();

        if (participantCount <= 0) {
            sender.sendMessage(ChatColor.RED + "참가 가능한 플레이어가 없습니다.");
            sender.sendMessage(ChatColor.GRAY + "(SURVIVAL 또는 ADVENTURE 상태인 플레이어만 참가)");
            return;
        }

        running = true;
        remainingTime = 150 + (30 * participantCount);
        maxTime = remainingTime;

        setupTeamPrefix();
        createBossBar();
        startTimer();

        int normalReward = getNormalRewardSeconds();
        int deathPenalty = getDeathPenaltySeconds();

        sender.sendMessage(ChatColor.GREEN + "도전과제 생존 협동모드를 시작했습니다.");
        sender.sendMessage(ChatColor.YELLOW + "참가자 수: " + participantCount + "명");
        sender.sendMessage(ChatColor.YELLOW + "시작 시간: " + remainingTime + "초");

        broadcastToParticipants(ChatColor.LIGHT_PURPLE + "[도전과제 생존] "
                + ChatColor.GREEN + "협동모드가 시작되었습니다!");
        broadcastToParticipants(ChatColor.YELLOW + "참가자 수: " + participantCount + "명");
        broadcastToParticipants(ChatColor.YELLOW + "시작 시간: " + remainingTime + "초");
        broadcastToParticipants(ChatColor.YELLOW + "일반 도전과제 보상: +" + normalReward + "초");
        broadcastToParticipants(ChatColor.YELLOW + "사망 패널티: -" + deathPenalty + "초");
    }

    private void stopGame(boolean notify, CommandSender sender) {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }

        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }

        clearTeamPrefix();

        if (notify) {
            broadcastToParticipants(ChatColor.RED + "도전과제 생존이 종료되었습니다.");
            if (sender != null) {
                sender.sendMessage(ChatColor.GREEN + "도전과제 생존을 종료했습니다.");
            }
        }

        participants.clear();
        participantNames.clear();
        participantCount = 0;
        remainingTime = 0;
        maxTime = 0;
        running = false;
    }

    private void startTimer() {
        timerTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!running) return;

            remainingTime--;
            updateBossBar();

            if (remainingTime <= 0) {
                broadcastToParticipants(ChatColor.RED + "제한시간이 종료되었습니다.");
                for (UUID uuid : participants) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        p.sendTitle(
                                ChatColor.RED + "실패",
                                ChatColor.GRAY + "제한시간 종료",
                                10, 60, 20
                        );
                    }
                }
                stopGame(false, null);
            }
        }, 20L, 20L);
    }

    private void createBossBar() {
        bossBar = Bukkit.createBossBar(
                ChatColor.GOLD + "도전과제 생존 | 남은 시간: " + ChatColor.WHITE + remainingTime + "초",
                BarColor.GREEN,
                BarStyle.SOLID
        );

        for (UUID uuid : participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                bossBar.addPlayer(player);
            }
        }

        bossBar.setVisible(true);
        bossBar.setProgress(1.0);
    }

    private void updateBossBar() {
        if (bossBar == null) return;

        double progress = Math.max(0.0, Math.min(1.0, (double) remainingTime / Math.max(1, maxTime)));
        bossBar.setProgress(progress);
        bossBar.setTitle(ChatColor.GOLD + "도전과제 생존 | 남은 시간: " + ChatColor.WHITE + remainingTime + "초");

        if (remainingTime <= 30) {
            bossBar.setColor(BarColor.RED);
        } else if (remainingTime <= 90) {
            bossBar.setColor(BarColor.YELLOW);
        } else {
            bossBar.setColor(BarColor.GREEN);
        }
    }

    private void addTime(int seconds) {
        remainingTime += seconds;
        maxTime += seconds;
        updateBossBar();
    }

    private void subtractTime(int seconds) {
        remainingTime -= seconds;
        if (remainingTime < 0) remainingTime = 0;
        updateBossBar();
    }

    private int getNormalRewardSeconds() {
        return Math.max(1, 120 / participantCount);
    }

    private int getChallengeRewardSeconds() {
        return Math.max(1, 240 / participantCount);
    }

    private int getDeathPenaltySeconds() {
        return participantCount * 60;
    }

    private boolean isParticipant(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    private boolean isActiveParticipant(Player player) {
        return running && participants.contains(player.getUniqueId());
    }

    private void broadcastToParticipants(String message) {
        for (UUID uuid : participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        }
    }

    private void kickAllParticipants(String message) {
        for (UUID uuid : participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.kickPlayer(message);
            }
        }
    }

    private void setupTeamPrefix() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam(TEAM_NAME);

        if (team == null) {
            team = scoreboard.registerNewTeam(TEAM_NAME);
        }

        team.setPrefix(ChatColor.LIGHT_PURPLE + "[도전과제 생존] " + ChatColor.RESET);

        for (String name : participantNames.values()) {
            if (!team.hasEntry(name)) {
                team.addEntry(name);
            }
        }
    }

    private void clearTeamPrefix() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam(TEAM_NAME);
        if (team == null) return;

        for (String name : participantNames.values()) {
            if (team.hasEntry(name)) {
                team.removeEntry(name);
            }
        }

        if (team.getEntries().isEmpty()) {
            team.unregister();
        }
    }

    @EventHandler
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        if (!isActiveParticipant(player)) return;

        Advancement advancement = event.getAdvancement();
        NamespacedKey key = advancement.getKey();
        String fullKey = key.toString();

        // 레시피 해금 제외
        if (key.getNamespace().equalsIgnoreCase("minecraft")
                && key.getKey().startsWith("recipes/")) {
            return;
        }


        if (fullKey.equals("minecraft:adventure/root")) return;

        int reward = getNormalRewardSeconds();
        addTime(reward);

        broadcastToParticipants(ChatColor.AQUA + "[도전과제] "
                + ChatColor.WHITE + player.getName()
                + ChatColor.AQUA + " 이(가) "
                + ChatColor.WHITE + key.getKey()
                + ChatColor.GREEN + " 달성! +" + reward + "초");

        broadcastToParticipants(ChatColor.YELLOW + "현재 팀 공용 남은 시간: "
                + ChatColor.WHITE + remainingTime + "초");
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!isActiveParticipant(player)) return;

        int penalty = getDeathPenaltySeconds();
        subtractTime(penalty);

        broadcastToParticipants(ChatColor.RED + "[사망 패널티] "
                + ChatColor.WHITE + player.getName()
                + ChatColor.RED + " 사망! -" + penalty + "초");
        broadcastToParticipants(ChatColor.YELLOW + "현재 팀 공용 남은 시간: "
                + ChatColor.WHITE + remainingTime + "초");

        if (remainingTime <= 0) {
            broadcastToParticipants(ChatColor.RED + "제한시간이 종료되었습니다.");
            kickAllParticipants(ChatColor.RED + "도전과제 생존 제한시간이 종료되었습니다.");
            stopGame(false, null);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!running) return;

        Player player = event.getPlayer();
        if (!participants.contains(player.getUniqueId())) return;

        if (bossBar != null) {
            bossBar.addPlayer(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!running) return;

        Player player = event.getPlayer();
        if (!participants.contains(player.getUniqueId())) return;

        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
    }
}