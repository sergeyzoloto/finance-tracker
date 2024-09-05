import { Routes, Route } from "react-router-dom";
import { LoginForm } from "./components/LoginForm";

import "./App.css";

function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginForm />} />
    </Routes>
  );
}

export default App;
