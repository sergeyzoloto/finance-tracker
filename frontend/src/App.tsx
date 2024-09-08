import { Routes, Route } from "react-router-dom";
import WelcomePage from "./pages/WelcomePage/WelcomePage";
import InfoPage from "./pages/InfoPage/InfoPage";
import AuthChecker from "./context/AuthChecker";

import "./App.css";

function App() {
  return (
    <Routes>
      <Route path="/login" element={<WelcomePage />} />
      <Route
        path="/*"
        element={
          <AuthChecker>
            {
              /* protected routes */
              <Route path="/" element={<InfoPage />} />
            }
          </AuthChecker>
        }
      />
    </Routes>
  );
}

export default App;
