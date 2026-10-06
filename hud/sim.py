"""
A simulated Q50 Hybrid drive, so the whole pipeline -- poller, server, display -- runs on a
desk with no car and no adapter. Shapes, not physics: it creeps away in EV, the engine joins
under load and at speed, regeneration tops the battery up when braking, and the coolant warms
from cold.
"""

import math
import random


class Drive(object):
    def __init__(self, seed=7):
        self.r = random.Random(seed)
        self.t = 0.0
        self.speed = 0.0
        self.target = 0.0
        self.hold = 0.0
        self.soc = 55.0
        self.coolant = 32.0
        self.rpm = 0.0
        self.throttle = 0.0

    def step(self, dt):
        self.t += dt
        self.hold -= dt
        if self.hold <= 0:                     # pick the next leg of the trip
            self.target = self.r.choice([0, 0, 30, 45, 60, 80, 100])
            self.hold = self.r.uniform(6, 14)

        err = self.target - self.speed
        accel = max(-9.0, min(5.0, err * 0.6))  # km/h per second
        self.speed = max(0.0, self.speed + accel * dt)
        self.throttle = max(0.0, min(100.0, accel * 14 + self.speed * 0.25)) if accel > -0.5 else 0.0

        # The engine joins under hard throttle, at motorway speed, or when the battery is low.
        engine = self.throttle > 45 or self.speed > 85 or self.soc < 35
        want_rpm = (1100 + self.speed * 18 + self.throttle * 22) if engine else 0.0
        self.rpm += (want_rpm - self.rpm) * min(1.0, dt * 4)
        if self.rpm < 300 and not engine:
            self.rpm = 0.0

        if accel < -1.0:                       # braking regenerates
            self.soc += -accel * dt * 0.05
        elif engine:
            self.soc += dt * 0.08
        elif self.speed > 1:
            self.soc -= dt * (0.04 + self.throttle * 0.002)
        self.soc = max(20.0, min(80.0, self.soc))

        self.coolant += (88 - self.coolant) * dt * (0.02 if engine else 0.004)

        return {
            "speed": round(self.speed),
            "rpm": round(self.rpm),
            "throttle": round(self.throttle, 1),
            "load": round(self.throttle * 0.8, 1),
            "coolant": round(self.coolant),
            "soc": round(self.soc, 1),
            "volt": 14.1,
            "fuel": 62.0,
            "ambient": 24,
            "iat": 30,
            "mil": False,
            "dtc_count": 0,
        }
