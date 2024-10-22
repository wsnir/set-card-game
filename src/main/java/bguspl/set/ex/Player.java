package bguspl.set.ex;
import bguspl.set.Env;
import java.util.concurrent.ArrayBlockingQueue;

/**
    * This class manages the players' threads and data
    * @inv id >= 0
    * @inv score >= 0
    */
@SuppressWarnings("BusyWait")
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    // Game entities:

    private final Table table;

    public final int id;

    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    private int score;

    private final Dealer dealer;

    /**
     * The action queue.
     */
    private final ArrayBlockingQueue<Integer> actions;
    
    /**
     * Whether keys should be registered.
     */
    private volatile boolean waitingToTest = false;

    protected static final int point = 1;

    protected static final int penalty = -1;

    protected static final int outdated = 0;

    private static final int unpicked = -1;

    protected volatile int isValidSet = outdated;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.dealer = dealer;
        this.table = table;
        this.id = id;
        this.human = human;
        actions = new ArrayBlockingQueue<>(env.config.featureSize, true);
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");

        if (!human) createArtificialIntelligence();
        else dealer.getDealerThread().interrupt();

        while (!terminate) {
            int slot = unpicked;
            // consumes an action from the queue
            try{
                slot = actions.take();
            } catch (InterruptedException actionOrTerminate) {
                if (terminate){
                    if (!human){
                        aiThread.interrupt();
                    }
                    break;
                }
            }
            if (!table.removeToken(id, slot)){
                table.placeToken(id, slot);
            }

            // delivers a set to test
            if (table.shouldTest(id)){
                waitingToTest = true;
                try{
                    dealer.setsToTest.put(this);
                } catch (InterruptedException ignore) {}
                dealer.getDealerThread().interrupt();
                try{
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException ignored) {
                    if (terminate){
                        if (!human){
                            aiThread.interrupt();
                        }
                        break;
                    }
                }
                if (!terminate && isValidSet != outdated){
                    if (isValidSet == point){
                        point();
                    }
                    else if (isValidSet == penalty){
                        penalty();
                    }
                    isValidSet = outdated;
                }
                actions.clear();
                waitingToTest = false;
                if (!human){
                    aiThread.interrupt();
                }
            }
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            dealer.getDealerThread().interrupt();
            while (!terminate){
                // shouldn't press any keys
                if (waitingToTest || !dealer.canPlace){
                    try{
                        Thread.sleep(Long.MAX_VALUE);
                    } catch (InterruptedException ignored) {if (terminate) {break;}}
                }
                // presses random valid keys
                keyPressed((int) (env.config.tableSize * Math.random()));
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    public void terminate() {
        terminate = true;
        playerThread.interrupt();
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (!waitingToTest && (!human || actions.remainingCapacity() > 0) && dealer.canPlace){
            try {
                actions.put(slot);
            } catch (InterruptedException ignored) {}
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        env.ui.setScore(id, ++score);
        freeze(env.config.pointFreezeMillis);
    }

    public void penalty() {
        freeze(env.config.penaltyFreezeMillis);
    }
    
    public int score() {
        return score;
    }

    protected Thread getPlayerThread() {
        return playerThread;
    }

    protected boolean isHuman(){
        return human;
    }

    protected Thread aiThread(){
        return aiThread;
    }

    private void freeze(long time){
        long freezeTime = System.currentTimeMillis() + time;
        try {
            while(System.currentTimeMillis() < freezeTime) {
                long freezeFor = freezeTime - System.currentTimeMillis();
                env.ui.setFreeze(id, freezeFor);
                Thread.sleep(Math.max(freezeFor % Table.refreshTime, 0));
            }
        } catch (InterruptedException ignore) {}
        env.ui.setFreeze(id, 0);
    }
}
