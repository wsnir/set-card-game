package bguspl.set.ex;
import bguspl.set.Env;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    // Game entities:

    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The dealer's thread.
     */
    private Thread dealerThread;

    /**
     * A queue of sets that wait to be tested.
     */
    protected final ArrayBlockingQueue<Player> setsToTest;

    /**
     * Marks where to remove cards from.
     */
    private int[] slotsToReplace;

    /**
     * Indicates if a reshuffle occurred recently
     */
    private boolean reshuffle;
    
    /**
     * Whether players can place tokens
     */
    protected volatile boolean canPlace = false;

    private static final int sleepIfWarning = 10;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param table  - the table object.
     * @param players - the player list
     */
    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        setsToTest = new ArrayBlockingQueue<>(env.config.players, true);
        slotsToReplace = new int[env.config.featureSize];
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        dealerThread = Thread.currentThread();
        startPlayers();
        while (!shouldFinish()){
            Collections.shuffle(deck);
            reshuffle = true;
            placeCardsOnTable();
            wakeSleeping();
            timerLoop();
            removeAllCardsFromTable();
        }
        announceWinners();
        termPlayers();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            while (!terminate && !setsToTest.isEmpty()){
                try{
                    // consumes and handles queried sets
                    Player player = setsToTest.take();
                    boolean hadSet = examineSet(player);
                    updateTimerDisplay(false);
                    player.getPlayerThread().interrupt();
                    if(hadSet){
                        removeCardsFromTable();
                        placeCardsOnTable();
                    }
                } catch (InterruptedException ignored) {}
            }
        }
        canPlace = false;
    }

    /**
     * Wakes players that had sets to test during a timeout
     */
    private void wakeSleeping(){
        for (Player player : setsToTest){
            player.getPlayerThread().interrupt();
        }
        setsToTest.clear();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        dealerThread.interrupt();
        env.ui.dispose();
    }

    /**
     * terminates the players threads in FIFO order  
     */
    private void termPlayers(){
        canPlace = true;
        for (int i = players.length-1; i >= 0; --i){
            players[i].terminate();
            try {
                players[i].getPlayerThread().join();
            } catch (InterruptedException ignored) {}
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).isEmpty();
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        for (int i : slotsToReplace) {
            table.removeCard(i);
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        int numToPlace = env.config.tableSize - table.countCards();
        int slotToPlace = 0;
        if (!reshuffle){
            updateTimerDisplay(true);
        }
        while (numToPlace > 0 && !deck.isEmpty()){
            // tracks the next available slot
            while (table.slotToCard[slotToPlace] != null){
                ++slotToPlace;
            }
            int card = deck.remove(deck.size() - 1);
            table.placeCard(card, slotToPlace);
            --numToPlace;
            if (!reshuffle){
                updateTimerDisplay(false);
            }
        }
        if (reshuffle){
            canPlace = true;
            updateTimerDisplay(true);
            for (Player player : players){
                if (!player.isHuman()){
                    player.aiThread().interrupt();
                }
            }
        }
        reshuffle = false;
        if (env.config.hints){
            table.hints();
        }
    }
   
    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    @SuppressWarnings("BusyWait")
    private void sleepUntilWokenOrTimeout() {
        try {
            while(System.currentTimeMillis() < reshuffleTime) {
                if (reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis){
                    Thread.sleep(sleepIfWarning);
                }
                else {
                    Thread.sleep(Math.max(reshuffleTime % Table.refreshTime, 0));
                }
                updateTimerDisplay(false);
            }
        } catch (InterruptedException ignored) {}
    }

    /**
     * Starts the player threads
     */
    private void startPlayers(){
        for (Player player : players){
            (new Thread(player)).start();
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException ignored) {}
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset){
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            env.ui.setCountdown(env.config.turnTimeoutMillis, env.config.turnTimeoutMillis <= env.config.turnTimeoutWarningMillis);
        }
        else {
            env.ui.setCountdown(Math.max(reshuffleTime - System.currentTimeMillis(), 0) , reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        for(int slot = 0; slot < env.config.tableSize; ++slot){
            if(table.slotToCard[slot] != null){
                deck.add(table.slotToCard[slot]);
                table.removeCard(slot);
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int maxScore = 0;
        int size = 0;
        for (Player player : players){
            if (player.score() > maxScore){
                maxScore = player.score();
                size = 1;
            }
            else if (player.score() == maxScore){
                ++size;
            }
        }
        int[] winners = new int[size];
        int curr = 0;
        for (Player player : players){
            if (player.score() == maxScore){
                winners[curr] = player.id;
                ++curr;
            }
        }
        env.ui.announceWinner(winners);
    }
    
    /**
     * Checks the validity and relevance of a given set
     */
    private boolean examineSet(Player player){
        if (!table.shouldTest(player.id)){
            // outdated set
            player.isValidSet = Player.outdated;
            return false;
        }
        int[] slots = table.getPlayerTokens(player.id);
        // the cards in the given slots
        int[] cards = new int[slots.length];
        for (int i = 0; i < cards.length; ++i){
            cards[i] = table.slotToCard[slots[i]];
        }
        if(env.util.testSet(cards)){
            // point
            player.isValidSet = Player.point; 
            // cards to remove
            slotsToReplace = slots; 
            return true;
        }
        // penalty
        player.isValidSet = Player.penalty;
        return false;
    }

    /**
     * Returns the dealer thread
     */
    protected Thread getDealerThread(){
        return dealerThread;
    }
}
