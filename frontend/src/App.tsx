import { Routes, Route } from "react-router-dom";
import WelcomePage from "./pages/WelcomePage/WelcomePage";

import "./App.css";

function App() {
  return (
    <Routes>
      <Route path="/login" element={<WelcomePage />} />
    </Routes>
  );
}

export default App;
