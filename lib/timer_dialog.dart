import 'package:flutter/material.dart';
import 'dart:async';

class ActiveTimerDialog extends StatefulWidget {
  final String appName;
  const ActiveTimerDialog({Key? key, required this.appName}) : super(key: key);

  @override
  _ActiveTimerDialogState createState() => _ActiveTimerDialogState();
}

class _ActiveTimerDialogState extends State<ActiveTimerDialog> {
  int timeLeft = 0;
  Timer? timer;
  bool isRunning = false;

  void startTimer(int minutes) {
    setState(() {
      timeLeft = minutes * 60;
      isRunning = true;
    });
    timer = Timer.periodic(const Duration(seconds: 1), (Timer t) {
      if (timeLeft <= 0) {
        t.cancel();
        // Time khatam hone ke baad dialogue close hoga
        Navigator.pop(context, true); 
      } else {
        setState(() {
          timeLeft--;
        });
      }
    });
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (isRunning) {
      return AlertDialog(
        backgroundColor: Colors.black87,
        title: const Text("Session Active", style: TextStyle(color: Colors.white)),
        content: Text(
          "${(timeLeft ~/ 60).toString().padLeft(2, '0')}:${(timeLeft % 60).toString().padLeft(2, '0')} remaining",
          style: const TextStyle(fontSize: 32, fontWeight: FontWeight.bold, color: Colors.white),
          textAlign: TextAlign.center,
        ),
      );
    }

    return AlertDialog(
      backgroundColor: Colors.grey[900],
      title: Text("How long in ${widget.appName}?", style: const TextStyle(color: Colors.white)),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          ListTile(
            title: const Text("5 Minutes", style: TextStyle(color: Colors.white)),
            onTap: () => startTimer(5),
          ),
          ListTile(
            title: const Text("10 Minutes", style: TextStyle(color: Colors.white)),
            onTap: () => startTimer(10),
          ),
          ListTile(
            title: const Text("15 Minutes", style: TextStyle(color: Colors.white)),
            onTap: () => startTimer(15),
          ),
        ],
      ),
    );
  }
}

