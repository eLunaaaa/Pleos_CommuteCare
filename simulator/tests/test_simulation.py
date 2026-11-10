import unittest
from simulator.server import CommuteSimulationHandler, MOCK_STATE

class TestCommuteSimulation(unittest.TestCase):
    def test_mock_state_structure(self):
        self.assertIn("vehicle", MOCK_STATE)
        self.assertIn("current_mode", MOCK_STATE)
        self.assertIn("cabin_temp", MOCK_STATE["vehicle"])
        self.assertIn("target_temp", MOCK_STATE["vehicle"])

if __name__ == "__main__":
    unittest.main()
