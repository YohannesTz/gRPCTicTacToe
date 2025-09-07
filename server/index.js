const grpc = require('@grpc/grpc-js');
const protoLoader = require('@grpc/proto-loader');
const { v4: uuidv4 } = require('uuid');
const path = require('path');

//const PROTO_PATH = path.join(__dirname, '..', 'proto', 'tictactoe.proto');
const PROTO_PATH = path.join(__dirname, '..', 'app', 'src', 'main', 'proto', 'tictactoe.proto');
const packageDef = protoLoader.loadSync(PROTO_PATH, { keepCase: false, longs: String, enums: String, defaults: true, oneofs: true });
const proto = grpc.loadPackageDefinition(packageDef).tictactoe;

const games = new Map();
function createEmptyBoard() { return Array(9).fill(''); }

function checkWinner(board) {
  const lines = [
    [0,1,2],[3,4,5],[6,7,8],
    [0,3,6],[1,4,7],[2,5,8],
    [0,4,8],[2,4,6]
  ];
  for (const [a,b,c] of lines) {
    if (board[a] && board[a] === board[b] && board[a] === board[c]) {
      return board[a];
    }
  }
  if (board.every(cell => cell)) return 'DRAW';
  return null;
}

function broadcastState(gameId) {
  const g = games.get(gameId);
  if (!g) return;
  const state = {
    gameId,
    board: g.board,
    nextTurn: g.nextTurn,
    status: g.status,
    players: g.players
  };
  for (const call of g.streams) {
    try { call.write(state); } catch (e) { }
  }
}

const serviceImpl = {
  CreateGame: (call, callback) => {
    const playerName = call.request.playerName || 'Player1';
    const gameId = uuidv4().slice(0,8);
    const board = createEmptyBoard();
    const players = [{ name: playerName, symbol: 'X' }]; 
    const g = { board, players, nextTurn: 'X', status: 'IN_PROGRESS', streams: new Set() };
    games.set(gameId, g);
    callback(null, { gameId, yourSymbol: 'X' });
  },



   MakeMove: (call, callback) => {
    const { gameId, playerName, row, col } = call.request;
    const g = games.get(gameId);
    if (!g) return callback(null, { ok: false, message: 'Game not found' });
    if (g.status !== 'IN_PROGRESS') return callback(null, { ok: false, message: 'Game ended' });

    
    const p = g.players.find(x => x.name === playerName);
    if (!p || !p.symbol) return callback(null, { ok: false, message: 'Player not in game or spectator' });
    const symbol = p.symbol;
    if (g.nextTurn !== symbol) return callback(null, { ok: false, message: "Not your turn" });

    const idx = row * 3 + col;
    if (idx < 0 || idx > 8 || g.board[idx]) return callback(null, { ok: false, message: "Invalid move" });

    g.board[idx] = symbol;

    
    const winner = checkWinner(g.board);
    if (winner === 'DRAW') {
      g.status = 'DRAW';
    } else if (winner === 'X' || winner === 'O') {
      g.status = (winner === 'X') ? 'X_WON' : 'O_WON';
    } else {
      g.nextTurn = (g.nextTurn === 'X') ? 'O' : 'X';
    }

    
    broadcastState(gameId);

    callback(null, { ok: true, message: 'Move accepted' });
  },

  GetState: (call, callback) => {
    const { gameId } = call.request;
    const g = games.get(gameId);
    if (!g) return callback(null, {});
    callback(null, {
      gameId,
      board: g.board,
      nextTurn: g.nextTurn,
      status: g.status,
      players: g.players
    });
  }
};


function main() {
  const server = new grpc.Server();
  server.addService(proto.TicTacToe.service, serviceImpl);
  const addr = '0.0.0.0:50051';
  server.bindAsync(addr, grpc.ServerCredentials.createInsecure(), () => {
    server.start();
    console.log('loaded proto: ' + PROTO_PATH);
    console.log('gRPC server listening on', addr);
  });
}

main();
