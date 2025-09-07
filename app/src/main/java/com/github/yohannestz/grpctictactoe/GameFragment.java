package com.github.yohannestz.grpctictactoe;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.github.yohannestz.grpctictactoe.databinding.FragmentGameBinding;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("SetTextI18n")
public class GameFragment extends Fragment {

    private FragmentGameBinding binding;

    private ManagedChannel channel;
    private TicTacToeGrpc.TicTacToeStub asyncStub;
    private TicTacToeGrpc.TicTacToeBlockingStub blockingStub;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final Button[] cells = new Button[9];
    private String myName;
    private String mySymbol;
    private String currentGameId;
    private GameState latestState;
    private String previousStatus = "";

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentGameBinding.inflate(inflater, container, false);

        for (int i = 0; i < 9; i++) {
            int id = getResources().getIdentifier("cell" + i, "id", requireActivity().getPackageName());
            cells[i] = binding.getRoot().findViewById(id);
            final int idx = i;
            cells[i].setOnClickListener(v -> onCellClicked(idx));
        }

        channel = ManagedChannelBuilder.forAddress(BuildConfig.GRPC_HOST, BuildConfig.GRPC_PORT)
                .usePlaintext()
                .build();
        asyncStub = TicTacToeGrpc.newStub(channel);
        blockingStub = TicTacToeGrpc.newBlockingStub(channel);

        binding.btnCreate.setOnClickListener(v -> createGame());
        binding.btnJoin.setOnClickListener(v -> joinGame());

        return binding.getRoot();
    }

    private void createGame() {
        myName = binding.etName.getText().toString();
        if (myName.isEmpty()) {
            Toast.makeText(getContext(), "Enter name", Toast.LENGTH_SHORT).show();
            return;
        }
        executor.execute(() -> {
            CreateRequest req = CreateRequest.newBuilder().setPlayerName(myName).build();
            CreateResponse resp = blockingStub.createGame(req);
            currentGameId = resp.getGameId();
            mySymbol = resp.getYourSymbol();

            requireActivity().runOnUiThread(() -> {
                binding.etGameId.setText(currentGameId);
                binding.tvStatus.setText("Created game " + currentGameId + " as " + mySymbol);
            });

            joinStream(currentGameId, myName);
        });
    }

    private void joinGame() {
        myName = binding.etName.getText().toString();
        currentGameId = binding.etGameId.getText().toString();
        if (myName.isEmpty() || currentGameId.isEmpty()) {
            Toast.makeText(getContext(), "Fill name and game id", Toast.LENGTH_SHORT).show();
            return;
        }

        executor.execute(() -> {
            StateRequest sr = StateRequest.newBuilder().setGameId(currentGameId).build();
            try {
                GameState initialState = blockingStub.getState(sr);
                requireActivity().runOnUiThread(() -> updateUI(initialState));
            } catch (Exception e) {
                Log.e("GameFragment", "Failed to get initial state", e);
            }
        });

        joinStream(currentGameId, myName);
    }

    private void joinStream(String gameId, String playerName) {
        executor.execute(() -> {
            try {
                JoinRequest jr = JoinRequest.newBuilder().setGameId(gameId).setPlayerName(playerName).build();
                asyncStub.joinGame(jr, new StreamObserver<>() {
                    @Override
                    public void onNext(GameState gameState) {
                        latestState = gameState;
                        for (Player p : gameState.getPlayersList()) {
                            if (p.getName().equals(playerName)) {
                                mySymbol = p.getSymbol();
                            }
                        }
                        requireActivity().runOnUiThread(() -> updateUI(gameState));
                    }

                    @Override
                    public void onError(Throwable t) {
                        Log.e("FirstFragment", "Stream error", t);
                        requireActivity().runOnUiThread(() ->
                                binding.tvStatus.setText("Stream error: " + t.getMessage()));
                    }

                    @Override
                    public void onCompleted() {
                        requireActivity().runOnUiThread(() ->
                                binding.tvStatus.setText("Stream closed"));
                    }
                });
            } catch (Exception e) {
                Log.e("GameFragment", "Failed to join stream", e);
                requireActivity().runOnUiThread(() ->
                        binding.tvStatus.setText("Stream join failed: " + e.getMessage()));
            }
        });
    }

    private void updateUI(GameState state) {
        Log.d("GameFragment", "Updating UI - Status: " + state.getStatus() +
                ", Next: " + state.getNextTurn() + ", MySymbol: " + mySymbol);

        checkForGameResult(state);

        binding.tvStatus.setText("Game: " + state.getGameId() +
                " Status: " + state.getStatus() +
                " Next: " + state.getNextTurn());

        if (!state.getPlayersList().isEmpty()) {
            StringBuilder playersText = new StringBuilder("Players: ");
            for (Player player : state.getPlayersList()) {
                playersText.append(player.getName())
                        .append(" (")
                        .append(player.getSymbol())
                        .append("), ");
            }
            if (playersText.length() > 2) {
                playersText.setLength(playersText.length() - 2);
            }
            binding.tvPlayers.setText(playersText.toString());
            binding.tvPlayers.setVisibility(View.VISIBLE);
        } else {
            binding.tvPlayers.setVisibility(View.GONE);
        }

        java.util.List<String> board = state.getBoardList();
        for (int i = 0; i < 9; i++) {
            String v = board.get(i);
            cells[i].setText(v);

            boolean isEmpty = v.isEmpty();
            boolean isInProgress = state.getStatus().equals("IN_PROGRESS");
            boolean isMyTurn = mySymbol != null && mySymbol.equals(state.getNextTurn());

            cells[i].setEnabled(isEmpty && isInProgress && isMyTurn);

            Log.d("GameFragment", "Cell " + i + ": " + v +
                    ", enabled: " + (isEmpty && isInProgress && isMyTurn));
        }

        previousStatus = state.getStatus();
    }

    private void checkForGameResult(GameState state) {
        String currentStatus = state.getStatus();

        if (!currentStatus.equals(previousStatus)) {
            switch (currentStatus) {
                case "X_WON":
                    showGameResultAlert("Game Over",
                            "X Wins!",
                            mySymbol != null && mySymbol.equals("X") ? "You won!" : "You lost!");
                    break;
                case "O_WON":
                    showGameResultAlert("Game Over",
                            "O Wins!",
                            mySymbol != null && mySymbol.equals("O") ? "You won!" : "You lost!");
                    break;
                case "DRAW":
                    showGameResultAlert("Game Over", "It's a Draw!", "The game ended in a tie.");
                    break;
            }
        }
    }

    private void showGameResultAlert(String title, String message, String details) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(title)
                .setMessage(message + "\n\n" + details)
                .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                .setCancelable(false)
                .show();
    }

    private void onCellClicked(int idx) {
        if (currentGameId == null || myName == null || mySymbol == null) {
            Toast.makeText(getContext(), "Join a game first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (latestState == null || !latestState.getStatus().equals("IN_PROGRESS") ||
                !mySymbol.equals(latestState.getNextTurn())) {
            Toast.makeText(getContext(), "Not your turn or game not in progress", Toast.LENGTH_SHORT).show();
            return;
        }

        int row = idx / 3, col = idx % 3;
        executor.execute(() -> {
            MoveRequest mr = MoveRequest.newBuilder()
                    .setGameId(currentGameId)
                    .setPlayerName(myName)
                    .setRow(row)
                    .setCol(col)
                    .build();
            try {
                MoveResponse resp = blockingStub.makeMove(mr);
                requireActivity().runOnUiThread(() -> {
                    if (!resp.getOk()) {
                        Toast.makeText(getContext(), "Move failed: " + resp.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e("GameFragment", "Move failed", e);
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), "Move error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (channel != null && !channel.isShutdown()) channel.shutdownNow();
        executor.shutdownNow();
        binding = null;
    }

    public GameState getLatestState() {
        return latestState;
    }

    public void setLatestState(GameState latestState) {
        this.latestState = latestState;
    }
}