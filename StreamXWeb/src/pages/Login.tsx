import { useState, useCallback, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../services/api.js'
import './Login.css'

export const LoginPage = () => {
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = useCallback(
    async (e: FormEvent) => {
      e.preventDefault()
      
      if (!username.trim() || !password.trim()) {
        setError('Please enter both username and password')
        return
      }

      setLoading(true)
      setError(null)

      try {
        await api.login(username.trim(), password)
        // Redirect to home after successful login
        navigate('/', { replace: true })
        // Reload to refresh auth state
        window.location.reload()
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Login failed')
      } finally {
        setLoading(false)
      }
    },
    [username, password, navigate],
  )

  const handleClose = useCallback(() => {
    navigate(-1)
  }, [navigate])

  return (
    <div className="login-page">
      <button className="login-close-btn" type="button" onClick={handleClose} aria-label="Close">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M18 6L6 18M6 6l12 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
        </svg>
      </button>

      <div className="login-container">
        <div className="login-logo">
          <svg width="80" height="80" viewBox="0 0 80 80" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M40 0C17.909 0 0 17.909 0 40s17.909 40 40 40 40-17.909 40-40S62.091 0 40 0zm0 73.333C21.591 73.333 6.667 58.409 6.667 40S21.591 6.667 40 6.667 73.333 21.591 73.333 40 58.409 73.333 40 73.333z" fill="#8E8E93"/>
            <path d="M40 20c-11.046 0-20 8.954-20 20s8.954 20 20 20 20-8.954 20-20-8.954-20-20-20z" fill="#8E8E93"/>
          </svg>
        </div>

        <h1 className="login-title">Sign in with your Account</h1>
        <p className="login-subtitle">
          You will be signed in to
          <br />
          Music Streaming.
        </p>

        <form className="login-form" onSubmit={handleSubmit}>
          <div className="login-input-group">
            <input
              type="text"
              className="login-input"
              placeholder="Username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              disabled={loading}
              autoComplete="username"
            />
          </div>

          <div className="login-input-group">
            <input
              type="password"
              className="login-input login-input-password"
              placeholder="Password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={loading}
              autoComplete="current-password"
            />
            <button
              type="submit"
              className="login-submit-btn"
              disabled={loading || !username.trim() || !password.trim()}
              aria-label="Sign in"
            >
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M5 12h14m-7-7l7 7-7 7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
              </svg>
            </button>
          </div>

          {error && <div className="login-error">{error}</div>}
        </form>

        <div className="login-links">
          <button type="button" className="login-link" disabled>
            Create New Account ›
          </button>
          <button type="button" className="login-link" disabled>
            Forgot Password?
          </button>
        </div>
      </div>
    </div>
  )
}
