import 'package:flutter/material.dart';
import 'dart:async';
import 'dart:math'; // Max aur Min calculate karne ke liye

class ActiveTimerDialog extends StatefulWidget {
  final String appName;
  final int remainingQuotaMinutes; // Daily quota me se bacha hua time

  const ActiveTimerDialog({
    Key? key, 
    required this.appName,
    required this.remainingQuotaMinutes,
  }) : super(key: key);

  @override
  _ActiveTimerDialogState createState() => _ActiveTimerDialogState();
}

class _ActiveTimerDialogState extends State<ActiveTimerDialog> {
  int timeLeft = 0;
  Timer? timer;
  bool isRunning = false;

  void startTimer(int requestedMinutes) {
    // Asli logic yaha hai: requested time aur bache hue quota me se jo kam ho, wo set hoga
    int actualMinutes = min(requestedMinutes, widget.remainingQuotaMinutes);
    
    setState(() {
      timeLeft = actualMinutes * 60;
      isRunning = true;
    });
    
    timer = Timer.periodic(const Duration(seconds: 1), (Timer t) {
      if (timeLeft <= 0) {
        t.cancel();
        // Time up hote hi dialog close hoga aur wapas strict block lag jayega
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

  Widget _buildTimeButton(int minutes) {
    bool isAvailable = widget.remainingQuotaMinutes >= minutes;
    int displayTime = isAvailable ? minutes : widget.remainingQuotaMinutes;

    return ListTile(
      title: Text(
        isAvailable ? "$minutes Minutes" : "Max Limit ($displayTime Minutes)", 
        style: TextStyle(
          color: isAvailable ? Colors.white : Colors.grey,
          fontWeight: isAvailable ? FontWeight.normal : FontWeight.bold,
        )
      ),
      onTap: () => startTimer(displayTime),
    );
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
      title: Text("How long in ${widget.appName}?\n(Quota left: ${widget.remainingQuotaMinutes} min)", 
        style: const TextStyle(color: Colors.white, fontSize: 16)),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (widget.remainingQuotaMinutes > 0) ...[
            _buildTimeButton(5),
            _buildTimeButton(10),
            _buildTimeButton(15),
          ] else 
            const Padding(
              padding: EdgeInsets.all(8.0),
              child: Text("Daily limit reached! Padhai pe lag ja.", style: TextStyle(color: Colors.redAccent, fontWeight: FontWeight.bold)),
            )
        ],
      ),
    );
  }
}
